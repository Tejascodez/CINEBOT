package com.cinebot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.user.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real booking flows -> outbox events -> notification and analytics handlers. */
class BookingEventsIT extends PaymentTestSupport {

    @Autowired
    private OutboxProcessor relay;

    @Test
    void everyLifecycleStepPublishesExactlyOneEventInOrder() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        List<String> hooks = deliverWebhooks();
        assertThat(hooks).containsExactly("PROCESSED");

        assertThat(eventsOf(booking)).containsExactly("booking.created", "booking.payment_started", "booking.confirmed");

        // a duplicate webhook or a repeated verify changes nothing, so publishes nothing
        reconciliation.runOnce();
        assertThat(eventsOf(booking)).hasSize(3);
    }

    @Test
    void failedLockOrRejectedTransitionPublishesNothing() {
        var booking = book(alice, "A1");
        try {
            book(bob, "A1"); // seat taken: the whole attempt rolls back
        } catch (RuntimeException expected) {
            // expected
        }
        bookingService.cancel(alice, booking.id());
        try {
            bookingService.cancel(alice, booking.id()); // idempotent: no second event
            jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE id = ?", booking.id());
            bookingService.cancel(alice, booking.id()); // EXPIRED -> CANCELLED is invalid: throws
        } catch (RuntimeException expected) {
            // expected
        }
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY created_at", String.class))
                .containsExactly("booking.created", "booking.cancelled");
    }

    @Test
    void confirmationSendsTicketEmailAndCountsTheSale() throws Exception {
        BookingResponse booking = confirmed(alice, "A1", "B1");

        relay.processBatch();

        mvc.perform(get("/api/v1/notifications/my").header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("booking.confirmed"))
                .andExpect(jsonPath("$[0].subject").value("Booking confirmed: " + booking.reference()))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("CBT1." + booking.reference())))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("Interstellar")));
        mvc.perform(get("/api/v1/notifications/my").header("Authorization", bearerFor(bob)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/admin/stats/shows/{id}", show).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(jsonPath("$.bookingsConfirmed").value(1))
                .andExpect(jsonPath("$.seatsSold").value(2))
                .andExpect(jsonPath("$.revenuePaise").value(55_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status <> 'DONE'", Integer.class)).isZero();
    }

    @Test
    void refundOfASaleReversesAnalyticsAndNotifiesTwice() throws Exception {
        BookingResponse booking = confirmed(alice, "A1");
        bookingService.cancel(alice, booking.id());
        provider.processDueRefunds();
        deliverWebhooks();

        relay.processBatch();

        assertThat(subjects()).containsExactly("Refund completed: " + booking.reference(),
                "Refund initiated: " + booking.reference(), "Booking confirmed: " + booking.reference());
        assertThat(jdbc.queryForMap("SELECT bookings_confirmed, seats_sold, revenue_paise, refunds FROM show_sales_stats"))
                .containsEntry("bookings_confirmed", 0).containsEntry("seats_sold", 0)
                .containsEntry("revenue_paise", 0L).containsEntry("refunds", 1);
    }

    @Test
    void lateCaptureRefundIsNotASaleAndIsExplainedToTheCustomer() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        expireHold(booking, true);
        sweep.sweepBatch();
        deliverWebhooks(); // capture arrives after expiry

        relay.processBatch();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_sales_stats", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE kind = 'booking.refund_requested'", String.class))
                .contains("arrived after your seats were no longer available");
        assertThat(subjects()).anyMatch(s -> s.startsWith("Seat hold expired"));
    }

    @Test
    void paymentFailureNotifiesTheCustomer() throws Exception {
        var booking = book(alice, "A1");
        decline(order(alice, booking));
        deliverWebhooks();

        relay.processBatch();

        assertThat(subjects()).containsExactly("Payment failed: " + booking.reference());
    }

    @Test
    void reprocessingTheSameEventsChangesNothing() throws Exception {
        confirmed(alice, "A1");
        relay.processBatch();
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', processed_at = NULL"); // e.g. crash before DONE

        relay.processBatch();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT seats_sold FROM show_sales_stats", Integer.class)).isEqualTo(1);
    }

    private BookingResponse confirmed(com.cinebot.auth.AuthenticatedUser user, String... labels) throws Exception {
        BookingResponse booking = book(user, labels);
        pay(order(user, booking));
        deliverWebhooks();
        return booking;
    }

    private List<String> eventsOf(BookingResponse booking) {
        return jdbc.queryForList("""
                SELECT event_type FROM outbox_events WHERE aggregate_type = 'booking' AND aggregate_id = ?
                ORDER BY created_at""", String.class, String.valueOf(booking.id()));
    }

    private List<String> subjects() {
        return jdbc.queryForList("SELECT subject FROM notifications ORDER BY created_at DESC, id DESC", String.class);
    }
}
