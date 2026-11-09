package com.cinebot.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.payment.simulator.SimulatedWebhookPublisher.SignedWebhook;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.support.Race;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The failure scenarios from the spec: crashes, lost / duplicated / concurrent webhooks, and the race between
 * lock expiry and payment confirmation.
 */
class PaymentRecoveryIT extends PaymentTestSupport {

    /** Payment succeeds, backend crashes before confirming. Nothing is lost: the reconciler finishes the job. */
    @Test
    void crashAfterCaptureIsRecoveredByReconciliation() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        var paid = pay(order);
        faults.setCrashAfterCapture(true);

        mvc.perform(post("/api/v1/payments/verify").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderId":"%s","paymentId":"%s","signature":"%s"}"""
                                .formatted(order.orderId(), paid.paymentId(), paid.signature())))
                .andExpect(status().isInternalServerError());
        List<SignedWebhook> hooks = takeWebhooks();
        assertThat(deliver(hooks.getFirst())).isEqualTo("HTTP_500"); // provider will retry this one

        // the crash rolled back everything: our DB still says "waiting", the provider says "captured"
        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_PENDING");
        assertThat(paymentStatus(booking.id())).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_webhook_events", Integer.class)).isZero();

        faults.setCrashAfterCapture(false); // "process restarted"
        agePayments();
        var report = reconciliation.runOnce();

        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
        assertThat(seatStatus("A1")).isEqualTo("BOOKED");
        // the provider's retry of the failed webhook now lands as a harmless no-op
        assertThat(deliver(hooks.getFirst())).isEqualTo("PROCESSED");
        assertThat(reconciliation.runOnce().confirmed()).isZero();
    }

    @Test
    void lostWebhookAndClosedBrowserAreCaughtByReconciliation() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        takeWebhooks(); // webhook lost in transit

        agePayments();
        assertThat(reconciliation.runOnce().confirmed()).isEqualTo(1);
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void reconcilerLeavesFreshOrdersAloneAndExpiresAbandonedOnes() {
        var booking = book(alice, "A1");
        order(alice, booking);

        assertThat(reconciliation.runOnce()).isEqualTo(new PaymentReconciliation.Report(0, 0, 0, 0, 0));

        agePayments();
        jdbc.update("UPDATE payments SET order_expires_at = now() - interval '1 minute'");
        assertThat(reconciliation.runOnce().expired()).isEqualTo(1);
        assertThat(paymentStatus(booking.id())).isEqualTo("EXPIRED");
    }

    @Test
    void duplicateWebhooksDeliveredConcurrentlyApplyOnce() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        SignedWebhook hook = takeWebhooks().getFirst();

        var outcomes = Race.run(List.<Callable<String>>of(
                () -> deliver(hook), () -> deliver(hook), () -> deliver(hook), () -> deliver(hook), () -> deliver(hook)));

        assertThat(outcomes.stream().map(Race.Outcome::value).toList())
                .containsOnly("PROCESSED", "DUPLICATE")
                .filteredOn("PROCESSED"::equals).hasSize(1);
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void verifyAndWebhookRacingConfirmOnce() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        var paid = pay(order);
        SignedWebhook hook = takeWebhooks().getFirst();

        var outcomes = Race.run(List.<Callable<Object>>of(
                () -> deliver(hook),
                () -> mvc.perform(post("/api/v1/payments/verify").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderId":"%s","paymentId":"%s","signature":"%s"}"""
                                .formatted(order.orderId(), paid.paymentId(), paid.signature())))
                        .andReturn().getResponse().getStatus()));

        assertThat(outcomes.get(0).value()).isEqualTo("PROCESSED");
        assertThat(outcomes.get(1).value()).isEqualTo(200);
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE status = 'BOOKED'", Integer.class)).isEqualTo(1);
    }

    /** Lock TTL passed, but nobody took the seats: the late payment still gets them (ownership, not clock). */
    @Test
    void paymentJustAfterExpiryStillConfirmsWhenSeatsUntaken() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        expireHold(booking, false); // 30 s past TTL: inside the payment grace
        assertThat(sweep.sweepBatch()).isZero(); // sweeper leaves PAYMENT_PENDING bookings alone during grace

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
        assertThat(seatStatus("A1")).isEqualTo("BOOKED");
    }

    /** Lock TTL passed and another user took the seat: the paying user is refunded, the other keeps the seat. */
    @Test
    void paymentAfterSeatTakeoverIsRefunded() throws Exception {
        var booking = book(alice, "A1", "A2");
        var order = order(alice, booking);
        pay(order);
        expireHold(booking, false);
        var bobs = book(bob, "A2"); // lazy expiry: bob takes over A2 before alice's payment is processed

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("REFUND_PENDING");
        assertThat(paymentStatus(booking.id())).isEqualTo("REFUND_PENDING");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");        // alice's leftover seat released
        assertThat(jdbc.queryForObject("SELECT lock_id FROM show_seats WHERE id = ?", java.util.UUID.class,
                seat(show, "A2"))).isEqualTo(bobs.seatLockId()); // bob untouched
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM payments WHERE booking_id = ?", String.class,
                booking.id())).startsWith("rfnd_"); // refund started right after commit
    }

    @Test
    void paymentAfterSweeperExpiredBookingIsRefunded() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking));
        expireHold(booking, true); // beyond grace
        assertThat(sweep.sweepBatch()).isEqualTo(1);
        assertThat(bookingStatus(booking.id())).isEqualTo("EXPIRED");

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("REFUND_PENDING");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
    }

    @Test
    void captureForACancelledBookingIsRefunded() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        bookingService.cancel(alice, booking.id()); // customer gave up, but the bank completes the payment
        pay(order);

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("REFUND_PENDING");
        assertThat(paymentStatus(booking.id())).isEqualTo("REFUND_PENDING");
    }

    @Test
    void lateCaptureAfterAFailedAttemptIsRefundedEndToEnd() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        decline(order);
        deliverWebhooks();
        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_FAILED");

        pay(order); // a second attempt on the same order succeeds after we already failed the booking
        deliverWebhooks();
        assertThat(bookingStatus(booking.id())).isEqualTo("REFUND_PENDING");

        assertThat(provider.processDueRefunds()).isEqualTo(1);
        assertThat(deliverWebhooks()).containsExactly("PROCESSED"); // refund.processed
        assertThat(bookingStatus(booking.id())).isEqualTo("REFUNDED");
        assertThat(paymentStatus(booking.id())).isEqualTo("REFUNDED");
    }

    @Test
    void refundIsStartedAndCompletedByReconcilerWhenCallsAndWebhooksAreLost() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        bookingService.cancel(alice, booking.id());
        pay(order);
        deliverWebhooks();
        // simulate "refund call never happened" (e.g. crash right after commit)
        jdbc.update("DELETE FROM sim_refunds");
        jdbc.update("UPDATE payments SET provider_refund_id = NULL");

        assertThat(reconciliation.runOnce().refundsStarted()).isEqualTo(1);
        assertThat(reconciliation.runOnce().refundsStarted()).isZero(); // idempotent

        provider.processDueRefunds();
        takeWebhooks(); // refund.processed webhook lost too
        assertThat(reconciliation.runOnce().refundsCompleted()).isEqualTo(1);
        assertThat(bookingStatus(booking.id())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sim_refunds", Integer.class)).isEqualTo(1);
    }
}
