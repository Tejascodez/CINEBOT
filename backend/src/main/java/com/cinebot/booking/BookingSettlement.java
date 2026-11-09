package com.cinebot.booking;

import com.cinebot.booking.lock.SeatLock;
import com.cinebot.booking.lock.SeatLockLifecycle;
import com.cinebot.booking.lock.SeatLockRepository;
import com.cinebot.booking.lock.SeatLockStatus;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.seat.ShowSeatInventory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking module's API for money events, used by the payment module. Every method runs inside the
 * caller's transaction (MANDATORY), after {@link #lock} has taken the rows in the global order:
 * seat_locks -> bookings (-> payments, locked by the caller).
 */
@Service
public class BookingSettlement {

    private static final Logger log = LoggerFactory.getLogger(BookingSettlement.class);

    private final BookingRepository bookings;
    private final SeatLockRepository locks;
    private final SeatLockLifecycle lockLifecycle;
    private final ShowSeatInventory inventory;
    private final BookingTransitions transitions;

    public BookingSettlement(BookingRepository bookings, SeatLockRepository locks, SeatLockLifecycle lockLifecycle,
                             ShowSeatInventory inventory, BookingTransitions transitions) {
        this.bookings = bookings;
        this.locks = locks;
        this.lockLifecycle = lockLifecycle;
        this.inventory = inventory;
        this.transitions = transitions;
    }

    /** A booking and its seat lock, both row-locked. */
    public record Locked(Booking booking, SeatLock lock) {
    }

    /** Ownership check for payment endpoints, without loading the entity. */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(Long bookingId, Long userId) {
        return bookings.findSeatLockIdOwnedBy(bookingId, userId).isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(Long bookingId) {
        UUID lockId = bookings.findSeatLockIdById(bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND));
        SeatLock lock = locks.findByIdForUpdate(lockId).orElseThrow();
        Booking booking = lockLifecycle.bookingOf(lock).orElseThrow();
        return new Locked(booking, lock);
    }

    /** PENDING -> PAYMENT_PENDING when a payment order is created. The hold must still be valid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startPayment(Locked locked, Instant now) {
        Booking booking = locked.booking();
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PAYABLE, "Booking is " + booking.getStatus());
        }
        if (locked.lock().getStatus() != SeatLockStatus.ACTIVE || locked.lock().isExpired(now)) {
            throw new ApiException(ErrorCode.LOCK_EXPIRED, "Your seat hold expired. Please select seats again.");
        }
        transitions.move(booking, BookingStatus.PAYMENT_PENDING, now);
    }

    /**
     * Money was captured. Confirms the booking if the lock still owns every seat (even if its TTL passed and
     * nobody took them); otherwise the booking moves to REFUND_PENDING.
     * @return true if the customer has their seats; false means the caller must refund
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmPaid(Locked locked, Instant now) {
        Booking booking = locked.booking();
        switch (booking.getStatus()) {
            case CONFIRMED:
                return true;
            case PAYMENT_PENDING: {
                List<Long> booked = locked.lock().getStatus() == SeatLockStatus.ACTIVE
                        ? inventory.bookIfStillHeld(locked.lock().getId(), booking.getId(), booking.getSeats().size())
                        : List.of();
                if (!booked.isEmpty()) {
                    booking.getSeats().forEach(s -> s.setConfirmed(true));
                    transitions.move(booking, BookingStatus.CONFIRMED, now);
                    lockLifecycle.consume(locked.lock(), booked, now);
                    return true;
                }
                log.warn("Booking {} paid but its seats were taken over after expiry: refunding",
                        booking.getBookingReference());
                if (locked.lock().getStatus() == SeatLockStatus.ACTIVE) {
                    lockLifecycle.release(locked.lock(), now); // free whatever subset it still held
                }
                transitions.move(booking, BookingStatus.REFUND_PENDING, now);
                return false;
            }
            case EXPIRED, PAYMENT_FAILED, CANCELLED:
                // Late capture for a booking that already died: never keep the money silently.
                transitions.move(booking, BookingStatus.REFUND_PENDING, now);
                return false;
            default: // REFUND_PENDING / REFUNDED (already handled) or PENDING (no order: impossible)
                return false;
        }
    }

    /** The payment failed: the booking fails and its seats are released at once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentFailed(Locked locked, Instant now) {
        if (locked.booking().getStatus() != BookingStatus.PAYMENT_PENDING) {
            return; // already confirmed/cancelled/expired: a stale failure event changes nothing
        }
        transitions.move(locked.booking(), BookingStatus.PAYMENT_FAILED, now);
        if (locked.lock().getStatus() == SeatLockStatus.ACTIVE) {
            lockLifecycle.release(locked.lock(), now);
        }
    }

    /** User cancels a confirmed booking (before the cutoff): seats go back on sale, refund is owed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelConfirmed(Locked locked, Instant now) {
        Booking booking = locked.booking();
        inventory.unbook(booking.getId());
        booking.getSeats().forEach(s -> s.setConfirmed(false)); // the partial unique index allows resale
        transitions.move(booking, BookingStatus.REFUND_PENDING, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refunded(Locked locked, Instant now) {
        if (locked.booking().getStatus() == BookingStatus.REFUND_PENDING) {
            transitions.move(locked.booking(), BookingStatus.REFUNDED, now);
        }
    }
}
