package com.cinebot.notification;

import com.cinebot.booking.BookingEvent;
import com.cinebot.booking.ticket.TicketCodec;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.outbox.EventHandler;
import com.cinebot.outbox.OutboxMessage;
import com.cinebot.show.ShowService;
import com.cinebot.user.User;
import com.cinebot.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Customer emails for booking events. The message is stored (exactly once, thanks to processed_events and the
 * unique (event_id, channel)) and then handed to the sender. If sending throws, the transaction rolls back and
 * the event is retried. Remaining edge: if the send succeeds and the commit then fails, the email goes out
 * twice. External side effects can be at-least-once at best; for email that is acceptable.
 */
@Component
class NotificationHandler implements EventHandler {

    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserService users;
    private final ShowService shows;
    private final TicketCodec tickets;
    private final EmailSender email;
    private final Clock clock;

    NotificationHandler(ObjectMapper json, NamedParameterJdbcTemplate jdbc, UserService users, ShowService shows,
                        TicketCodec tickets, EmailSender email, Clock clock) {
        this.json = json;
        this.jdbc = jdbc;
        this.users = users;
        this.shows = shows;
        this.tickets = tickets;
        this.email = email;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "notification";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(BookingEvent.CONFIRMED, BookingEvent.PAYMENT_FAILED, BookingEvent.EXPIRED,
                BookingEvent.REFUND_REQUESTED, BookingEvent.REFUNDED);
    }

    @Override
    public void handle(OutboxMessage message) {
        BookingEvent event = read(message);
        User user = users.getById(event.userId());
        var show = shows.get(event.showId());
        String when = BusinessTime.toLocal(show.startTime().toInstant()).toLocalDateTime().toString().replace('T', ' ');
        String what = show.movieTitle() + " at " + show.theatreName() + ", " + when + " (seats " + String.join(", ", event.seats()) + ")";
        String amount = "₹" + event.totalPaise() / 100;

        String subject;
        String body;
        switch (message.type()) {
            case BookingEvent.CONFIRMED -> {
                subject = "Booking confirmed: " + event.reference();
                body = "Your tickets for " + what + " are confirmed. Paid " + amount + ".\n"
                        + "Show this QR code at the entrance:\n" + tickets.encode(event.reference(), event.bookingId());
            }
            case BookingEvent.PAYMENT_FAILED -> {
                subject = "Payment failed: " + event.reference();
                body = "Your payment for " + what + " did not go through. Your seats have been released; "
                        + "you can try booking again.";
            }
            case BookingEvent.EXPIRED -> {
                subject = "Seat hold expired: " + event.reference();
                body = "We held " + what + " for you, but the booking was not completed in time.";
            }
            case BookingEvent.REFUND_REQUESTED -> {
                subject = "Refund initiated: " + event.reference();
                body = event.fromStatus() == com.cinebot.booking.BookingStatus.CONFIRMED
                        ? "Your booking for " + what + " was cancelled. A refund of " + amount + " has been initiated."
                        : "Your payment of " + amount + " for " + what + " arrived after your seats were no longer "
                        + "available. It is being refunded in full.";
            }
            case BookingEvent.REFUNDED -> {
                subject = "Refund completed: " + event.reference();
                body = "Your refund of " + amount + " for " + what + " has been processed.";
            }
            default -> {
                return;
            }
        }
        jdbc.update("""
                INSERT INTO notifications (user_id, event_id, kind, channel, recipient, subject, body, created_at)
                VALUES (:userId, :eventId, :kind, 'EMAIL', :to, :subject, :body, :now)
                """, new MapSqlParameterSource("userId", user.getId()).addValue("eventId", message.id())
                .addValue("kind", message.type()).addValue("to", user.getEmail()).addValue("subject", subject)
                .addValue("body", body).addValue("now", Timestamp.from(clock.instant())));
        email.send(user.getEmail(), subject, body);
    }

    private BookingEvent read(OutboxMessage message) {
        try {
            return json.readValue(message.payload(), BookingEvent.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unreadable payload of event " + message.id(), e);
        }
    }
}
