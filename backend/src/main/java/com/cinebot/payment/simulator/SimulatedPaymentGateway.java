package com.cinebot.payment.simulator;

import com.cinebot.common.crypto.Hmac;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.payment.gateway.GatewayOrder;
import com.cinebot.payment.gateway.GatewayPayment;
import com.cinebot.payment.gateway.GatewayRefund;
import com.cinebot.payment.gateway.PaymentGateway;
import com.cinebot.payment.webhook.WebhookEvent;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A stand-in for an external payment provider, with its own durable state (sim_* tables).
 * Every method runs in its OWN transaction (REQUIRES_NEW), the way a remote system's state is independent of
 * ours: if our transaction rolls back, the provider's capture still exists, exactly as in real life.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final NamedParameterJdbcTemplate jdbc;
    private final SimulatorProperties properties;
    private final SimulatedWebhookPublisher webhooks;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SimulatedPaymentGateway(NamedParameterJdbcTemplate jdbc, SimulatorProperties properties,
                                   SimulatedWebhookPublisher webhooks, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "SIMULATED";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GatewayOrder createOrder(String receipt, long amountPaise, String currency, Instant expiresAt) {
        String id = newId("order_");
        jdbc.update("""
                INSERT INTO sim_orders (id, receipt, amount_paise, currency, status, expires_at, created_at)
                VALUES (:id, :receipt, :amount, :currency, 'CREATED', :expiresAt, :now)
                """, new MapSqlParameterSource("id", id).addValue("receipt", receipt).addValue("amount", amountPaise)
                .addValue("currency", currency).addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("now", Timestamp.from(clock.instant())));
        return new GatewayOrder(id, amountPaise, currency, expiresAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<GatewayPayment> fetchPayment(String paymentId) {
        return jdbc.query("SELECT * FROM sim_payments WHERE id = :id", new MapSqlParameterSource("id", paymentId),
                (rs, i) -> payment(rs)).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<GatewayPayment> fetchPaymentsForOrder(String orderId) {
        return jdbc.query("SELECT * FROM sim_payments WHERE order_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", orderId), (rs, i) -> payment(rs));
    }

    /** Idempotent: the same key (or a second refund for the same payment) returns the existing refund. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GatewayRefund refund(String paymentId, long amountPaise, String idempotencyKey) {
        Optional<GatewayRefund> existing = findRefund("payment_id = :p OR idempotency_key = :k",
                new MapSqlParameterSource("p", paymentId).addValue("k", idempotencyKey));
        if (existing.isPresent()) {
            return existing.get();
        }
        GatewayPayment payment = fetchPayment(paymentId).filter(GatewayPayment::captured)
                .orElseThrow(() -> new IllegalArgumentException("No captured payment " + paymentId));
        String id = newId("rfnd_");
        jdbc.update("""
                INSERT INTO sim_refunds (id, payment_id, idempotency_key, amount_paise, status, created_at)
                VALUES (:id, :p, :k, :amount, 'PENDING', :now)
                """, new MapSqlParameterSource("id", id).addValue("p", paymentId).addValue("k", idempotencyKey)
                .addValue("amount", Math.min(amountPaise, payment.amountPaise()))
                .addValue("now", Timestamp.from(clock.instant())));
        return new GatewayRefund(id, paymentId, amountPaise, GatewayRefund.Status.PENDING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<GatewayRefund> fetchRefund(String refundId) {
        return findRefund("id = :id", new MapSqlParameterSource("id", refundId));
    }

    @Override
    public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        return Hmac.matches(checkoutSignature(orderId, paymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return Hmac.matches(Hmac.sha256Hex(properties.webhookSecret(), rawBody), signature);
    }

    // ----- provider-side behaviour (the "hosted checkout" and back office) ----------------------------------

    public enum Outcome { SUCCESS, FAILURE }

    public record CheckoutResult(String orderId, String paymentId, GatewayPayment.Status status, String signature,
                                 String failureReason) {
    }

    /** What happens when the customer clicks Pay / the card is declined on the provider's checkout page. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CheckoutResult checkout(String orderId, Outcome outcome) {
        SimOrder order = jdbc.query("SELECT * FROM sim_orders WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", orderId), (rs, i) -> new SimOrder(rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getLong("amount_paise"), rs.getString("currency")))
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKOUT_ORDER_NOT_FOUND));
        Instant now = clock.instant();
        if ("PAID".equals(order.status()) || !order.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CHECKOUT_ORDER_CLOSED);
        }
        long amount = order.amountPaise();
        String paymentId = newId("pay_");
        boolean success = outcome == Outcome.SUCCESS;
        String reason = success ? null : "Card declined (simulated)";
        jdbc.update("""
                INSERT INTO sim_payments (id, order_id, amount_paise, status, failure_reason, created_at)
                VALUES (:id, :order, :amount, :status, :reason, :now)
                """, new MapSqlParameterSource("id", paymentId).addValue("order", orderId).addValue("amount", amount)
                .addValue("status", success ? "CAPTURED" : "FAILED").addValue("reason", reason)
                .addValue("now", Timestamp.from(now)));
        if (success) {
            jdbc.update("UPDATE sim_orders SET status = 'PAID' WHERE id = :id", new MapSqlParameterSource("id", orderId));
        }
        webhooks.publishAfterCommit(new WebhookEvent(newId("evt_"),
                success ? WebhookEvent.PAYMENT_CAPTURED : WebhookEvent.PAYMENT_FAILED, now,
                new WebhookEvent.Data(orderId, paymentId, null, amount, order.currency(), reason)));
        return new CheckoutResult(orderId, paymentId, success ? GatewayPayment.Status.CAPTURED : GatewayPayment.Status.FAILED,
                success ? checkoutSignature(orderId, paymentId) : null, reason);
    }

    /** Provider back office settles refunds after a delay and notifies us by webhook. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processDueRefunds() {
        Instant now = clock.instant();
        var due = jdbc.query("""
                SELECT r.id, r.payment_id, r.amount_paise, p.order_id FROM sim_refunds r
                JOIN sim_payments p ON p.id = r.payment_id
                WHERE r.status = 'PENDING' AND r.created_at <= :cutoff
                FOR UPDATE OF r SKIP LOCKED
                """, new MapSqlParameterSource("cutoff", Timestamp.from(now.minus(properties.refundProcessingDelay()))),
                (rs, i) -> new String[]{rs.getString("id"), rs.getString("payment_id"),
                        String.valueOf(rs.getLong("amount_paise")), rs.getString("order_id")});
        for (String[] r : due) {
            jdbc.update("UPDATE sim_refunds SET status = 'PROCESSED', processed_at = :now WHERE id = :id",
                    new MapSqlParameterSource("id", r[0]).addValue("now", Timestamp.from(now)));
            webhooks.publishAfterCommit(new WebhookEvent(newId("evt_"), WebhookEvent.REFUND_PROCESSED, now,
                    new WebhookEvent.Data(r[3], r[1], r[0], Long.valueOf(r[2]), "INR", null)));
        }
        return due.size();
    }

    private record SimOrder(String status, Instant expiresAt, long amountPaise, String currency) {
    }

    String checkoutSignature(String orderId, String paymentId) {
        return Hmac.sha256Hex(properties.keySecret(), orderId + "|" + paymentId);
    }

    private Optional<GatewayRefund> findRefund(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT * FROM sim_refunds WHERE " + where, params, (rs, i) -> new GatewayRefund(
                rs.getString("id"), rs.getString("payment_id"), rs.getLong("amount_paise"),
                GatewayRefund.Status.valueOf(rs.getString("status")))).stream().findFirst();
    }

    private static GatewayPayment payment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GatewayPayment(rs.getString("id"), rs.getString("order_id"), rs.getLong("amount_paise"),
                GatewayPayment.Status.valueOf(rs.getString("status")), rs.getString("failure_reason"));
    }

    private String newId(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 14; i++) {
            sb.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
