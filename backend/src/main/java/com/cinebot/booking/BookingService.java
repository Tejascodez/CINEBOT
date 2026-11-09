package com.cinebot.booking;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.dto.BookingListQuery;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.lock.SeatLock;
import com.cinebot.booking.lock.SeatLockLifecycle;
import com.cinebot.booking.lock.SeatLockRepository;
import com.cinebot.booking.lock.SeatLockStatus;
import com.cinebot.booking.ticket.TicketCodec;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.common.web.PageResponse;
import com.cinebot.seat.SeatLine;
import com.cinebot.seat.ShowSeatInventory;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.user.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking workflow up to "pending payment". Locking order on every path: seat_locks row, then bookings row.
 * Other users' bookings are reported as 404, not 403, so ids can't be probed for existence.
 */
@Service
public class BookingService {

    private static final int REFERENCE_ATTEMPTS = 5;

    private final BookingRepository bookings;
    private final SeatLockRepository locks;
    private final SeatLockLifecycle lockLifecycle;
    private final ShowSeatInventory inventory;
    private final ShowService showService;
    private final BookingReferenceGenerator references;
    private final BookingSettlement settlement;
    private final RefundRequester refundRequester;
    private final TicketCodec tickets;
    private final BookingProperties properties;
    private final BookingTransitions transitions;
    private final Clock clock;

    public BookingService(BookingRepository bookings, SeatLockRepository locks, SeatLockLifecycle lockLifecycle,
                          ShowSeatInventory inventory, ShowService showService,
                          BookingReferenceGenerator references, BookingSettlement settlement,
                          RefundRequester refundRequester, TicketCodec tickets, BookingProperties properties,
                          BookingTransitions transitions, Clock clock) {
        this.bookings = bookings;
        this.locks = locks;
        this.lockLifecycle = lockLifecycle;
        this.inventory = inventory;
        this.showService = showService;
        this.references = references;
        this.settlement = settlement;
        this.refundRequester = refundRequester;
        this.tickets = tickets;
        this.properties = properties;
        this.transitions = transitions;
        this.clock = clock;
    }

    public record CreatedBooking(BookingResponse booking, boolean created) {
    }

    /**
     * Turns an active lock into a PENDING booking. Idempotent per lock: a retried or double-clicked request
     * waits on the lock row, then finds and returns the booking the first request created.
     */
    @Transactional
    public CreatedBooking create(AuthenticatedUser user, UUID lockId) {
        Instant now = clock.instant();
        SeatLock lock = locks.findByIdForUpdate(lockId)
                .filter(l -> l.isOwnedBy(user.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.LOCK_NOT_FOUND));

        var existing = lockLifecycle.bookingOf(lock);
        if (existing.isPresent()) {
            return new CreatedBooking(toResponse(existing.get()), false);
        }
        if (lock.getStatus() != SeatLockStatus.ACTIVE) {
            throw new ApiException(ErrorCode.LOCK_NOT_ACTIVE, "Seat lock is " + lock.getStatus());
        }
        List<SeatLine> held = inventory.heldBy(lockId);
        // Seats can only be taken over after expiry, so a short count also means "expired".
        if (lock.isExpired(now) || held.size() != lock.getSeatCount()) {
            throw new ApiException(ErrorCode.LOCK_EXPIRED, "Your seat hold expired. Please select seats again.");
        }
        Booking booking = Booking.pending(uniqueReference(), user.id(), lock.getShowId(), lockId, held,
                lock.getExpiresAt());
        Booking saved = bookings.saveAndFlush(booking);
        transitions.created(saved, now);
        return new CreatedBooking(toResponse(saved), true);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(AuthenticatedUser user, Long bookingId) {
        return toResponse(visibleBooking(user, bookingId));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listMine(AuthenticatedUser user, BookingListQuery query) {
        var pageable = PageRequest.of(query.page(), query.size(), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Booking> page = query.status() == null
                ? bookings.findByUserId(user.id(), pageable)
                : bookings.findByUserIdAndStatus(user.id(), query.status(), pageable);
        // One query for all shows on the page instead of one per booking.
        Set<Long> showIds = page.getContent().stream().map(Booking::getShowId).collect(Collectors.toSet());
        Map<Long, ShowResponse> shows = showService.getMany(showIds);
        return PageResponse.from(page, b -> toResponse(b, shows.get(b.getShowId())));
    }

    /**
     * Unpaid booking: CANCELLED, seats freed. Confirmed booking (until the cutoff before showtime):
     * REFUND_PENDING, seats back on sale, refund requested. Idempotent for already-cancelled/refunding bookings.
     */
    @Transactional
    public BookingResponse cancel(AuthenticatedUser user, Long bookingId) {
        // Read only the lock id first (a projection, so no entity is cached yet), then lock rows in the global
        // order lock row -> booking row. Loading the booking entity first would let Hibernate hand back that
        // cached, possibly stale instance from the later FOR UPDATE query.
        UUID lockId = bookings.findSeatLockIdOwnedBy(bookingId, user.id()).orElseThrow(() -> notFound(bookingId));
        SeatLock lock = locks.findByIdForUpdate(lockId).orElseThrow();
        Booking booking = lockLifecycle.bookingOf(lock).orElseThrow();

        Instant now = clock.instant();
        switch (booking.getStatus()) {
            case CANCELLED, REFUND_PENDING, REFUNDED -> { }
            case PENDING, PAYMENT_PENDING -> {
                // A payment completing later for this booking is refunded (see BookingSettlement.confirmPaid).
                transitions.move(booking, BookingStatus.CANCELLED, now);
                if (lock.getStatus() == SeatLockStatus.ACTIVE) {
                    lockLifecycle.release(lock, now);
                }
            }
            case CONFIRMED -> {
                Instant showStart = showService.get(booking.getShowId()).startTime().toInstant();
                if (!now.isBefore(showStart.minus(properties.cancellationCutoff()))) {
                    throw new ApiException(ErrorCode.CANCELLATION_WINDOW_CLOSED,
                            "Bookings can be cancelled until " + properties.cancellationCutoff().toHours()
                                    + "h before the show");
                }
                settlement.cancelConfirmed(new BookingSettlement.Locked(booking, lock), now);
                refundRequester.requestRefund(booking.getId());
            }
            default -> transitions.move(booking, BookingStatus.CANCELLED, now); // throws INVALID_STATE_TRANSITION
        }
        return toResponse(booking);
    }

    private Booking visibleBooking(AuthenticatedUser user, Long bookingId) {
        return bookings.findById(bookingId)
                .filter(b -> b.isOwnedBy(user.id()) || user.role() == Role.ADMIN)
                .orElseThrow(() -> notFound(bookingId));
    }

    private String uniqueReference() {
        for (int i = 0; i < REFERENCE_ATTEMPTS; i++) {
            String candidate = references.next();
            if (!bookings.existsByBookingReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking reference");
    }

    private BookingResponse toResponse(Booking booking) {
        return toResponse(booking, showService.get(booking.getShowId()));
    }

    private BookingResponse toResponse(Booking b, ShowResponse show) {
        var showInfo = show == null ? null : new BookingResponse.ShowInfo(show.id(), show.movieId(), show.movieTitle(),
                show.theatreName(), show.city(), show.screenName(), show.startTime());
        var seats = b.getSeats().stream()
                .map(s -> new BookingResponse.SeatInfo(s.getShowSeatId(), s.getSeatLabel(), s.getSeatType(), s.getPricePaise()))
                .toList();
        return new BookingResponse(b.getId(), b.getBookingReference(), b.getStatus(), showInfo, seats,
                b.getTotalAmountPaise(), b.getCurrency(), b.getSeatLockId(), BusinessTime.toLocal(b.getExpiresAt()),
                BusinessTime.toLocal(b.getCreatedAt()), BusinessTime.toLocal(b.getConfirmedAt()),
                b.getStatus() == BookingStatus.CONFIRMED
                        ? new BookingResponse.Ticket(tickets.encode(b.getBookingReference(), b.getId())) : null);
    }

    private static ApiException notFound(Long bookingId) {
        return new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found");
    }
}
