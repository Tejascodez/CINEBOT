package com.cinebot.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.BookingService;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.lock.ExpiredLockSweep;
import com.cinebot.booking.lock.SeatLockService;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.payment.PaymentFaults;
import com.cinebot.payment.PaymentOrderService;
import com.cinebot.payment.PaymentReconciliation;
import com.cinebot.payment.dto.PaymentOrderResponse;
import com.cinebot.payment.simulator.SimulatedPaymentGateway;
import com.cinebot.payment.simulator.SimulatedPaymentGateway.CheckoutResult;
import com.cinebot.payment.simulator.SimulatedPaymentGateway.Outcome;
import com.cinebot.payment.simulator.SimulatedWebhookPublisher;
import com.cinebot.payment.simulator.SimulatedWebhookPublisher.SignedWebhook;
import com.cinebot.payment.webhook.WebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Booking + payment fixtures. Webhooks produced by the simulator are queued and delivered explicitly. */
public abstract class PaymentTestSupport extends VenueTestSupport {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected SeatLockService seatLocks;
    @Autowired
    protected BookingService bookingService;
    @Autowired
    protected PaymentOrderService paymentOrders;
    @Autowired
    protected SimulatedPaymentGateway provider;
    @Autowired
    protected SimulatedWebhookPublisher webhookPublisher;
    @Autowired
    protected PaymentReconciliation reconciliation;
    @Autowired
    protected ExpiredLockSweep sweep;
    @Autowired
    protected PaymentFaults faults;

    protected long show;
    protected AuthenticatedUser alice;
    protected AuthenticatedUser bob;

    @BeforeEach
    void paymentFixtures() {
        webhookPublisher.drain();
        long screen = screenWithLayout(theatre("PVR Velachery", "Chennai"), "S1");
        show = show(movie("Interstellar", 150), screen, ist(tomorrow(), 18, 0)).id();
        alice = user("alice");
        bob = user("bob");
    }

    @AfterEach
    void disarmFaults() {
        faults.setCrashAfterCapture(false);
    }

    protected BookingResponse book(AuthenticatedUser user, String... labels) {
        List<Long> ids = Arrays.stream(labels).map(l -> seat(show, l)).toList();
        var lock = seatLocks.lock(user, new LockSeatsRequest(show, ids));
        return bookingService.create(user, lock.lockId()).booking();
    }

    protected PaymentOrderResponse order(AuthenticatedUser user, BookingResponse booking) {
        return paymentOrders.createOrder(user, booking.id());
    }

    protected CheckoutResult pay(PaymentOrderResponse order) {
        return provider.checkout(order.orderId(), Outcome.SUCCESS);
    }

    protected CheckoutResult decline(PaymentOrderResponse order) {
        return provider.checkout(order.orderId(), Outcome.FAILURE);
    }

    /** Delivers every webhook the provider has produced so far. @return each response's "status" */
    protected List<String> deliverWebhooks() throws Exception {
        List<String> acks = new java.util.ArrayList<>();
        for (SignedWebhook w : webhookPublisher.drain()) {
            acks.add(deliver(w));
        }
        return acks;
    }

    protected List<SignedWebhook> takeWebhooks() {
        return webhookPublisher.drain();
    }

    protected String deliver(SignedWebhook w) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .header(SimulatedWebhookPublisher.SIGNATURE_HEADER, w.signature()).content(w.body())).andReturn();
        if (r.getResponse().getStatus() != 200) {
            return "HTTP_" + r.getResponse().getStatus();
        }
        return json.readTree(r.getResponse().getContentAsString()).get("status").asText();
    }

    /** A webhook with a VALID signature but arbitrary content (as if the provider sent it). */
    protected SignedWebhook signed(String type, String orderId, String paymentId, long amount) {
        return webhookPublisher.sign(new WebhookEvent("evt_test_" + System.nanoTime(), type, Instant.now(),
                new WebhookEvent.Data(orderId, paymentId, null, amount, "INR", null)));
    }

    protected String bookingStatus(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    protected String paymentStatus(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE booking_id = ?", String.class, bookingId);
    }

    protected String seatStatus(String label) {
        return jdbc.queryForObject("SELECT status FROM show_seats WHERE id = ?", String.class, seat(show, label));
    }

    /** Moves the seat hold of a booking into the past (beyond the payment grace if {@code beyondGrace}). */
    protected void expireHold(BookingResponse booking, boolean beyondGrace) {
        String age = beyondGrace ? "3 minutes" : "10 seconds"; // inside the 2 min grace even if the machine stalls
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '" + age + "' WHERE id = ?", booking.seatLockId());
        jdbc.update("UPDATE show_seats SET locked_until = now() - interval '" + age + "' WHERE lock_id = ?",
                booking.seatLockId());
        redis.keys("cinebot:seatlock:*").forEach(redis::delete);
    }

    /** Makes CREATED payments old enough for the reconciler to look at. */
    protected void agePayments() {
        jdbc.update("UPDATE payments SET created_at = now() - interval '5 minutes'");
    }
}
