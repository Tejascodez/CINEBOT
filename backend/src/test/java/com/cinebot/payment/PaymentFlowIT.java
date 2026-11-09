package com.cinebot.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.payment.dto.PaymentOrderResponse;
import com.cinebot.payment.simulator.SimulatedWebhookPublisher;
import com.cinebot.payment.webhook.WebhookEvent;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.support.Race;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Order -> checkout -> verify / webhook -> CONFIRMED, plus the failure path and request validation. */
class PaymentFlowIT extends PaymentTestSupport {

    @Test
    void happyPathOverHttpConfirmsBookingAndIssuesTicket() throws Exception {
        var booking = book(alice, "A1", "B1");

        String orderJson = mvc.perform(post("/api/v1/payments/orders").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":" + booking.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amountPaise").value(55_000))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.checkoutUrl").value(org.hamcrest.Matchers.startsWith("/checkout/order_")))
                .andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(orderJson).get("orderId").asText();
        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_PENDING");

        // the browser goes to the provider's checkout and the customer clicks Pay
        String checkout = mvc.perform(post("/api/v1/payments/simulator/checkout").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var cb = json.readTree(checkout);

        mvc.perform(post("/api/v1/payments/verify").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderId":"%s","paymentId":"%s","signature":"%s"}"""
                                .formatted(orderId, cb.get("paymentId").asText(), cb.get("signature").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.ticket.qrPayload").value(org.hamcrest.Matchers.startsWith("CBT1." + booking.reference())));

        assertThat(seatStatus("A1")).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE booking_id = ?", Integer.class, booking.id()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM seat_locks WHERE id = ?", String.class, booking.seatLockId()))
                .isEqualTo("CONSUMED");
        assertThat(jdbc.queryForList("SELECT confirmed FROM booking_seats", Boolean.class)).containsOnly(true);
        assertThat(paymentStatus(booking.id())).isEqualTo("CAPTURED");
        assertThat(redis.keys("cinebot:seatlock:*")).isEmpty();

        // the provider's webhook for the same capture arrives afterwards: applied idempotently, no change
        List<SimulatedWebhookPublisher.SignedWebhook> hooks = takeWebhooks();
        assertThat(hooks).hasSize(1);
        assertThat(deliver(hooks.getFirst())).isEqualTo("PROCESSED");
        assertThat(deliver(hooks.getFirst())).isEqualTo("DUPLICATE"); // provider re-delivers the same event
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void webhookAloneConfirmsWhenBrowserNeverCallsBack() throws Exception {
        var booking = book(alice, "A1");
        pay(order(alice, booking)); // customer paid, then closed the tab

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
        assertThat(seatStatus("A1")).isEqualTo("BOOKED");
    }

    @Test
    void failedPaymentReleasesSeatsImmediately() throws Exception {
        var booking = book(alice, "A1");
        decline(order(alice, booking));

        assertThat(deliverWebhooks()).containsExactly("PROCESSED");

        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_FAILED");
        assertThat(paymentStatus(booking.id())).isEqualTo("FAILED");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM seat_locks WHERE id = ?", String.class, booking.seatLockId()))
                .isEqualTo("RELEASED");
        book(bob, "A1"); // someone else can take it at once
    }

    @Test
    void orderCreationIsIdempotentEvenUnderConcurrency() throws Exception {
        var booking = book(alice, "A1");

        var outcomes = Race.run(List.<Callable<PaymentOrderResponse>>of(
                () -> order(alice, booking), () -> order(alice, booking), () -> order(alice, booking),
                () -> order(alice, booking), () -> order(alice, booking)));

        assertThat(outcomes).allMatch(com.cinebot.support.Race.Outcome::succeeded);
        assertThat(outcomes.stream().map(o -> o.value().orderId()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isEqualTo(1);
        assertThat(order(alice, booking).orderId()).isEqualTo(outcomes.getFirst().value().orderId());
    }

    @Test
    void orderPreconditions() {
        var booking = book(alice, "A1");
        assertCode(() -> paymentOrders.createOrder(bob, booking.id()), ErrorCode.BOOKING_NOT_FOUND);

        bookingService.cancel(alice, booking.id());
        assertCode(() -> order(alice, booking), ErrorCode.BOOKING_NOT_PAYABLE);

        var other = book(alice, "A2");
        expireHold(other, false);
        assertCode(() -> order(alice, other), ErrorCode.LOCK_EXPIRED);
    }

    @Test
    void verifyRejectsForgedOrForeignOrUncapturedPayments() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        var paid = pay(order);

        verify(alice, order.orderId(), paid.paymentId(), "0".repeat(64))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_SIGNATURE"));
        verify(bob, order.orderId(), paid.paymentId(), paid.signature())
                .andExpect(status().isNotFound());
        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_PENDING");

        var booking2 = book(bob, "A2");
        var order2 = order(bob, booking2);
        var declined = decline(order2);
        // a signature that is valid for orderId|paymentId does not help if the provider says it was not captured
        verify(bob, order2.orderId(), declined.paymentId(),
                com.cinebot.common.crypto.Hmac.sha256Hex("test-payment-key-secret-0123456789",
                        order2.orderId() + "|" + declined.paymentId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CAPTURED"));
    }

    @Test
    void webhookSecurityAndValidation() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        var forged = signed(WebhookEvent.PAYMENT_CAPTURED, order.orderId(), "pay_forged", order.amountPaise());

        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header(SimulatedWebhookPublisher.SIGNATURE_HEADER, "deadbeef").content(forged.body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK"));
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(forged.body()))
                .andExpect(status().isBadRequest());
        // body tampered after signing
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header(SimulatedWebhookPublisher.SIGNATURE_HEADER, forged.signature())
                        .content(forged.body().replace(String.valueOf(order.amountPaise()), "1")))
                .andExpect(status().isBadRequest());
        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void businessRejectionsAreAcknowledgedAndRecordedSoProviderStopsRetrying() throws Exception {
        var booking = book(alice, "A1");
        var order = order(alice, booking);
        var wrongAmount = signed(WebhookEvent.PAYMENT_CAPTURED, order.orderId(), "pay_x1", 1);
        var unknownOrder = signed(WebhookEvent.PAYMENT_CAPTURED, "order_unknown", "pay_x2", 100);

        assertThat(deliver(wrongAmount)).isEqualTo("REJECTED");
        assertThat(deliver(wrongAmount)).isEqualTo("DUPLICATE");
        assertThat(deliver(unknownOrder)).isEqualTo("REJECTED");
        assertThat(deliver(signed("payment.something_new", order.orderId(), null, 0))).isEqualTo("IGNORED");

        assertThat(bookingStatus(booking.id())).isEqualTo("PAYMENT_PENDING");
        assertThat(paymentStatus(booking.id())).isEqualTo("CREATED");
        assertThat(jdbc.queryForList("SELECT outcome FROM payment_webhook_events ORDER BY received_at", String.class))
                .containsExactly("REJECTED: PAYMENT_AMOUNT_MISMATCH", "REJECTED: PAYMENT_NOT_FOUND", "IGNORED");
    }

    private org.springframework.test.web.servlet.ResultActions verify(com.cinebot.auth.AuthenticatedUser user,
                                                                       String orderId, String paymentId, String sig)
            throws Exception {
        return mvc.perform(post("/api/v1/payments/verify").header("Authorization", bearerFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"signature\":\"%s\"}".formatted(orderId, paymentId, sig)));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(code);
    }
}
