package com.cinebot.agent.tools;

import com.cinebot.booking.BookingProperties;
import com.cinebot.booking.BookingService;
import com.cinebot.booking.BookingStatus;
import com.cinebot.booking.dto.BookingListQuery;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.lock.SeatLockService;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.seat.ShowSeatStatus;
import com.cinebot.seat.dto.SeatMapResponse;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.ShowResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Booking operations. Backs POST /api/v1/seat-locks + POST /api/v1/bookings (hold), GET /bookings/{id},
 * GET /bookings/my and POST /bookings/{id}/cancel, through the same service methods.
 * <p>
 * Every state-changing operation is split into prepare (read-only: validates and shows a summary) and confirm
 * (executes), with the {@link ConfirmationGate} between them. Payment is deliberately NOT a capability: the agent
 * can only show a "Confirm &amp; Pay" button; the payment API is called by the user's click.
 */
@Component
public class BookingTools {

    private static final Set<BookingStatus> OPEN = EnumSet.of(BookingStatus.PENDING, BookingStatus.PAYMENT_PENDING);
    private static final Set<BookingStatus> CANCELLABLE =
            EnumSet.of(BookingStatus.PENDING, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED);

    record SeatLine(String seat, String type, String price) {
    }

    record BookingItem(Long bookingId, String reference, String status, String movie, String theatre, String showTime,
                       List<String> seats, String total, String holdExpiresAt) {
    }

    private final ShowService shows;
    private final SeatLockService seatLocks;
    private final BookingService bookings;
    private final BookingProperties bookingProperties;
    private final ConfirmationGate gate;

    public BookingTools(ShowService shows, SeatLockService seatLocks, BookingService bookings,
                        BookingProperties bookingProperties, ConfirmationGate gate) {
        this.shows = shows;
        this.seatLocks = seatLocks;
        this.bookings = bookings;
        this.bookingProperties = bookingProperties;
        this.gate = gate;
    }

    // ------------------------------------------------------------------------------------------------ hold seats

    @Tool(name = "prepareSeatHold", description = """
            Step 1 of booking: check that specific seats are free and prepare a booking summary (movie, theatre, \
            showtime, seats, total price) for the user to approve. This does NOT hold or book anything. \
            Use it once the user has chosen a showtime and seats (or accepted suggested seats). Then show the \
            summary and ask the user to confirm; never call confirmSeatHold in the same reply.""")
    public ToolReply prepareSeatHold(
            @ToolParam(description = "showId from findShowtimes") Long showId,
            @ToolParam(description = "Seat labels, e.g. [\"B1\",\"B2\"]") List<String> seats,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        ShowResponse show = ShowChecks.requireBookable(shows, ToolArgs.requireId(showId, "showId"), turn.now());
        List<String> labels = seatLabels(seats);
        refuseIfAlreadyHolding(turn, show.id());

        SeatMapResponse map = shows.seatMap(show.id());
        Map<String, SeatMapResponse.SeatView> byLabel = new HashMap<>();
        map.rows().forEach(r -> r.seats().forEach(s -> byLabel.put(s.label(), s)));
        List<String> unknown = labels.stream().filter(l -> !byLabel.containsKey(l)).toList();
        if (!unknown.isEmpty()) {
            return ToolReply.error("UNKNOWN_SEATS", "This show has no seat(s) " + String.join(", ", unknown)
                    + ". Use getSeatLayout to see valid seats.");
        }
        List<String> taken = labels.stream()
                .filter(l -> byLabel.get(l).status() != ShowSeatStatus.AVAILABLE).toList();
        if (!taken.isEmpty()) {
            return ToolReply.error("SEATS_UNAVAILABLE", String.join(", ", taken)
                    + (taken.size() == 1 ? " is" : " are") + " not available. Offer other seats.");
        }

        List<SeatMapResponse.SeatView> chosen = labels.stream().map(byLabel::get).toList();
        long total = chosen.stream().mapToLong(SeatMapResponse.SeatView::pricePaise).sum();
        // Select the show BEFORE preparing: switching shows voids a pending hold, which must not be this one.
        ShowChecks.remember(turn, show);
        PendingAction action = gate.prepare(turn, PendingAction.Type.HOLD_SEATS, show.id(),
                chosen.stream().map(SeatMapResponse.SeatView::showSeatId).toList(), labels, total, null, null);
        turn.state().setSeats(labels);
        turn.state().setTicketCount(labels.size());

        List<SeatLine> lines = chosen.stream()
                .map(s -> new SeatLine(s.label(), s.type().name(), Display.rupees(s.pricePaise()))).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("confirmationId", action.id());
        summary.put("movie", show.movieTitle());
        summary.put("theatre", show.theatreName() + ", " + show.city());
        summary.put("screen", show.screenName());
        summary.put("showTime", Display.when(show.startTime()));
        summary.put("seats", lines);
        summary.put("total", Display.rupees(total));
        summary.put("holdDurationMinutes", bookingProperties.lockTtl().toMinutes());
        turn.show(new AgentCard(AgentCard.Type.HOLD_CONFIRMATION_REQUIRED, summary));
        return ToolReply.ok("Summary prepared; NOTHING is held yet. Show the user every detail (movie, theatre, "
                + "show time, seats, total) and ask them to confirm. After they agree, call confirmSeatHold with "
                + "confirmationId " + action.id() + ".", summary);
    }

    @Tool(name = "confirmSeatHold", description = """
            Step 2 of booking: hold the seats from a prepared summary and create the booking, AFTER the user has \
            explicitly confirmed that summary (e.g. 'yes', 'go ahead', 'book it'). The seats are re-checked and \
            held for a few minutes; the user must then pay. Never call it without the user's confirmation.""")
    public ToolReply confirmSeatHold(
            @ToolParam(description = "confirmationId returned by prepareSeatHold") String confirmationId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        var action = gate.consume(turn, ToolArgs.requireText(confirmationId, "confirmationId", 20),
                PendingAction.Type.HOLD_SEATS);
        if (action.isEmpty()) {
            return alreadyDone(turn, "These seats are already held");
        }
        PendingAction hold = action.get();
        SeatLockResponse lock;
        try {
            // The authoritative re-check: atomic, all-or-nothing, against everyone else booking right now.
            lock = seatLocks.lock(turn.user(), new LockSeatsRequest(hold.showId(), hold.showSeatIds()));
        } catch (ApiException e) {
            if (e.code() == ErrorCode.SEATS_UNAVAILABLE) {
                return ToolReply.error("SEATS_UNAVAILABLE", takenLabels(e, hold)
                        + " just taken by someone else, so nothing was held. Offer other seats.");
            }
            throw e;
        }
        BookingResponse booking;
        try {
            booking = bookings.create(turn.user(), lock.lockId()).booking();
        } catch (RuntimeException e) {
            seatLocks.release(turn.user(), lock.lockId()); // never leave seats held without a booking
            throw e;
        }
        turn.state().recordBooking(booking.id(), booking.reference(), booking.status().name(), lock.lockId(),
                booking.seats().stream().map(BookingResponse.SeatInfo::label).toList());
        gate.executed(turn, hold, booking.id());
        turn.show(new AgentCard(AgentCard.Type.BOOKING_SUMMARY, booking));
        BookingItem item = item(booking);
        return ToolReply.ok("Seats " + String.join(", ", item.seats()) + " are HELD (not paid) until "
                + Display.time(booking.expiresAt()) + ". Booking " + booking.reference() + " is waiting for payment: "
                + "call requestPayment to show the payment button.", item);
    }

    // ------------------------------------------------------------------------------------------------ payment

    @Tool(name = "requestPayment", description = """
            Show the user a 'Confirm & Pay' button for a held booking. This does NOT charge anything and you \
            cannot pay on the user's behalf: the user must press the button. Afterwards use getBookingDetails \
            to see whether the payment went through.""")
    public ToolReply requestPayment(
            @ToolParam(required = false, description = "bookingId; defaults to the booking in this conversation")
            Long bookingId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        BookingResponse booking = bookings.get(turn.user(), bookingIdOrCurrent(turn, bookingId));
        turn.state().updateBookingStatus(booking.id(), booking.reference(), booking.status().name());
        if (!OPEN.contains(booking.status())) {
            return ToolReply.error("BOOKING_NOT_PAYABLE", "Booking " + booking.reference() + " is "
                    + booking.status() + " and cannot be paid.");
        }
        if (!booking.expiresAt().toInstant().isAfter(turn.now())) {
            return ToolReply.error("HOLD_EXPIRED", "The seat hold for " + booking.reference()
                    + " has expired. Offer to check the seats again.");
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("bookingId", booking.id());
        card.put("reference", booking.reference());
        card.put("amountPaise", booking.totalPaise());
        card.put("holdExpiresAt", booking.expiresAt());
        card.put("booking", booking);
        // The frontend performs this call when the user clicks; the agent never does.
        card.put("action", Map.of("method", "POST", "path", "/api/v1/payments/orders",
                "body", Map.of("bookingId", booking.id())));
        turn.show(new AgentCard(AgentCard.Type.PAYMENT_CONFIRMATION_REQUIRED, card));
        return ToolReply.ok("A 'Confirm & Pay' button for " + Display.rupees(booking.totalPaise())
                + " is shown. NOTHING has been charged; the user must press it before "
                + Display.time(booking.expiresAt()) + ". Do not say the booking is paid or confirmed.",
                Map.of("bookingId", booking.id(), "reference", booking.reference(),
                        "amount", Display.rupees(booking.totalPaise())));
    }

    // ------------------------------------------------------------------------------------------------ read

    @Tool(name = "getBookingDetails", description = """
            Get the current, real status and details of one of the user's bookings (the one in this \
            conversation if bookingId is omitted). Use it to answer 'did my payment go through?', 'show my \
            ticket', or before talking about a booking's status. Statuses: PENDING / PAYMENT_PENDING = held, \
            awaiting payment; CONFIRMED = paid, ticket issued; others = closed.""")
    public ToolReply getBookingDetails(
            @ToolParam(required = false, description = "bookingId from listMyBookings or this conversation")
            Long bookingId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        BookingResponse booking = bookings.get(turn.user(), bookingIdOrCurrent(turn, bookingId));
        turn.state().updateBookingStatus(booking.id(), booking.reference(), booking.status().name());
        turn.show(new AgentCard(booking.status() == BookingStatus.CONFIRMED
                ? AgentCard.Type.TICKET : AgentCard.Type.BOOKING_SUMMARY, booking));
        return ToolReply.ok("Booking " + booking.reference() + " is " + booking.status() + ".", item(booking));
    }

    @Tool(name = "listMyBookings", description = """
            List the user's own recent bookings, newest first, optionally only one status. Use it when the user \
            refers to a booking without an id ('my booking for Saturday', 'cancel my last booking').""")
    public ToolReply listMyBookings(
            @ToolParam(required = false, description = "Optional status filter, e.g. CONFIRMED") String status,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        BookingStatus filter = bookingStatus(status);
        List<BookingResponse> mine = bookings.listMine(turn.user(), new BookingListQuery(filter, 0, 10)).content();
        if (mine.isEmpty()) {
            return ToolReply.error("NO_BOOKINGS_FOUND", "The user has no " + (filter == null ? "" : filter + " ")
                    + "bookings.");
        }
        turn.show(new AgentCard(AgentCard.Type.BOOKINGS, mine));
        return ToolReply.ok(mine.size() + " booking(s), newest first.",
                Map.of("bookings", mine.stream().map(BookingTools::item).toList()));
    }

    // ------------------------------------------------------------------------------------------------ cancel

    @Tool(name = "prepareCancellation", description = """
            Step 1 of cancelling: check whether one of the user's bookings can be cancelled and prepare a summary \
            (what is cancelled, whether a refund follows). This does NOT cancel anything. Show the summary and \
            ask the user to confirm; never call confirmCancellation in the same reply.""")
    public ToolReply prepareCancellation(
            @ToolParam(description = "bookingId of the booking to cancel") Long bookingId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        BookingResponse booking = bookings.get(turn.user(), ToolArgs.requireId(bookingId, "bookingId"));
        if (!CANCELLABLE.contains(booking.status())) {
            return ToolReply.error("NOT_CANCELLABLE", "Booking " + booking.reference() + " is " + booking.status()
                    + " and cannot be cancelled.");
        }
        String consequence;
        if (booking.status() == BookingStatus.CONFIRMED) {
            Instant cutoff = booking.show().startTime().toInstant().minus(bookingProperties.cancellationCutoff());
            if (!turn.now().isBefore(cutoff)) {
                return ToolReply.error(ErrorCode.CANCELLATION_WINDOW_CLOSED.name(), "Bookings can be cancelled until "
                        + bookingProperties.cancellationCutoff().toHours() + "h before the show; this one can't be.");
            }
            consequence = "The ticket is cancelled and " + Display.rupees(booking.totalPaise())
                    + " is refunded to the original payment method.";
        } else {
            consequence = "The held seats are released. Nothing was charged.";
        }
        PendingAction action = gate.prepare(turn, PendingAction.Type.CANCEL_BOOKING, null, null, null,
                booking.totalPaise(), booking.id(), booking.reference());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("confirmationId", action.id());
        summary.put("booking", item(booking));
        summary.put("consequence", consequence);
        turn.show(new AgentCard(AgentCard.Type.CANCEL_CONFIRMATION_REQUIRED, summary));
        return ToolReply.ok("Cancellation prepared; NOTHING is cancelled yet. Tell the user: " + consequence
                + " Ask them to confirm, then call confirmCancellation with confirmationId " + action.id() + ".",
                summary);
    }

    @Tool(name = "confirmCancellation", description = """
            Step 2 of cancelling: cancel the booking from a prepared cancellation summary, AFTER the user has \
            explicitly confirmed it. Never call it without the user's confirmation.""")
    public ToolReply confirmCancellation(
            @ToolParam(description = "confirmationId returned by prepareCancellation") String confirmationId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        var action = gate.consume(turn, ToolArgs.requireText(confirmationId, "confirmationId", 20),
                PendingAction.Type.CANCEL_BOOKING);
        if (action.isEmpty()) {
            return alreadyDone(turn, "This cancellation was already carried out");
        }
        BookingResponse booking = bookings.cancel(turn.user(), action.get().bookingId());
        gate.executed(turn, action.get(), booking.id());
        turn.state().updateBookingStatus(booking.id(), booking.reference(), booking.status().name());
        turn.show(new AgentCard(AgentCard.Type.BOOKING_SUMMARY, booking));
        String outcome = switch (booking.status()) {
            case REFUND_PENDING -> "cancelled; the refund of " + Display.rupees(booking.totalPaise())
                    + " is being processed";
            case REFUNDED -> "cancelled and refunded";
            default -> booking.status().name().toLowerCase(Locale.ROOT);
        };
        return ToolReply.ok("Booking " + booking.reference() + " is " + outcome + " (status " + booking.status()
                + ").", item(booking));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * A repeated confirmation (double "yes", retried request) of an action that SUCCEEDED: report the booking that
     * action held or cancelled (not whatever booking the conversation is on now), and do nothing new.
     */
    private ToolReply alreadyDone(AgentTurn turn, String what) {
        BookingResponse booking = bookings.get(turn.user(), turn.state().getLastConfirmed().bookingId());
        turn.state().updateBookingStatus(booking.id(), booking.reference(), booking.status().name());
        return ToolReply.ok(what + " (no new action taken). Booking " + booking.reference() + " is "
                + booking.status() + ".", item(booking));
    }

    /** One open hold per show per user (the backend enforces it too): offer to pay or cancel it instead. */
    private void refuseIfAlreadyHolding(AgentTurn turn, long showId) {
        Long current = turn.state().getBookingId();
        if (current == null) {
            return;
        }
        BookingResponse booking = bookings.get(turn.user(), current);
        turn.state().updateBookingStatus(booking.id(), booking.reference(), booking.status().name());
        if (OPEN.contains(booking.status()) && booking.show() != null && booking.show().showId() == showId
                && booking.expiresAt().toInstant().isAfter(turn.now())) {
            throw new ApiException(ErrorCode.ACTIVE_LOCK_EXISTS, "The user already holds seats "
                    + String.join(", ", booking.seats().stream().map(BookingResponse.SeatInfo::label).toList())
                    + " for this show (booking " + booking.reference() + "). Offer to pay for it, or to cancel it "
                    + "before choosing different seats.");
        }
    }

    private static long bookingIdOrCurrent(AgentTurn turn, Long bookingId) {
        if (bookingId != null) {
            return ToolArgs.requireId(bookingId, "bookingId");
        }
        if (turn.state().getBookingId() == null) {
            throw new InvalidToolArgumentException("bookingId is required (no booking in this conversation yet; "
                    + "use listMyBookings)");
        }
        return turn.state().getBookingId();
    }

    private List<String> seatLabels(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw new InvalidToolArgumentException("seats is required, e.g. [\"B1\",\"B2\"]");
        }
        List<String> labels = new ArrayList<>();
        for (String seat : seats) {
            String label = seat == null ? "" : seat.trim().toUpperCase(Locale.ROOT);
            if (!label.matches("^[A-Z]{1,2}[0-9]{1,3}$")) {
                throw new InvalidToolArgumentException("'" + seat + "' is not a seat label like 'B7'");
            }
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        if (labels.size() > bookingProperties.maxSeatsPerLock()) {
            throw new InvalidToolArgumentException("at most " + bookingProperties.maxSeatsPerLock()
                    + " seats per booking");
        }
        return labels;
    }

    private static String takenLabels(ApiException e, PendingAction hold) {
        Object raw = e.properties().get("unavailableSeatIds");
        List<String> taken = new ArrayList<>();
        if (raw instanceof Collection<?> ids) {
            for (int i = 0; i < hold.showSeatIds().size(); i++) {
                long id = hold.showSeatIds().get(i);
                if (ids.stream().anyMatch(x -> x instanceof Number n && n.longValue() == id)) {
                    taken.add(hold.seatLabels().get(i));
                }
            }
        }
        return taken.isEmpty() ? "Some of the seats were"
                : String.join(", ", taken) + (taken.size() == 1 ? " was" : " were");
    }

    private static BookingStatus bookingStatus(String value) {
        String text = ToolArgs.optionalText(value, "status", 20);
        try {
            return text == null ? null : BookingStatus.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidToolArgumentException("status must be one of " + List.of(BookingStatus.values()));
        }
    }

    /** What the model sees about a booking: no QR payload, no lock ids, no payment internals. */
    static BookingItem item(BookingResponse b) {
        return new BookingItem(b.id(), b.reference(), b.status().name(),
                b.show() == null ? null : b.show().movieTitle(),
                b.show() == null ? null : b.show().theatreName(),
                b.show() == null ? null : Display.when(b.show().startTime()),
                b.seats().stream().map(BookingResponse.SeatInfo::label).toList(),
                Display.rupees(b.totalPaise()),
                OPEN.contains(b.status()) ? Display.time(b.expiresAt()) : null);
    }
}
