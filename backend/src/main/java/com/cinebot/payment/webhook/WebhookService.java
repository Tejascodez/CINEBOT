package com.cinebot.payment.webhook;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.payment.PaymentSettlementService;
import com.cinebot.payment.gateway.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook handling:
 * <ol>
 *   <li>verify the HMAC over the RAW body (re-serialising JSON would change bytes and break the signature);</li>
 *   <li>in ONE transaction: insert the event id (PK) and apply it. A duplicate delivery hits the PK and is
 *       acknowledged without effect. If applying fails, the insert rolls back too, so the provider's retry is
 *       processed rather than mistaken for a duplicate;</li>
 *   <li>business rejections (unknown order, amount mismatch) are recorded as processed with their outcome and
 *       acknowledged, so the provider stops retrying something that can never succeed;</li>
 *   <li>infrastructure failures propagate as 5xx: the provider retries later.</li>
 * </ol>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Ack { PROCESSED, DUPLICATE, IGNORED, REJECTED }

    private final PaymentGateway gateway;
    private final PaymentSettlementService settlement;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookService(PaymentGateway gateway, PaymentSettlementService settlement, NamedParameterJdbcTemplate jdbc,
                          TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.gateway = gateway;
        this.settlement = settlement;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    public Ack handle(String rawBody, String signature) {
        if (!gateway.verifyWebhookSignature(rawBody, signature)) {
            throw new ApiException(ErrorCode.INVALID_WEBHOOK, "Bad signature");
        }
        WebhookEvent event = parse(rawBody);
        try {
            return tx.execute(s -> {
                if (!insert(event, rawBody)) {
                    return Ack.DUPLICATE;
                }
                Ack ack = apply(event);
                markProcessed(event.id(), ack.name());
                return ack;
            });
        } catch (ApiException rejected) {
            log.error("Webhook {} ({}) rejected: {} {}", event.id(), event.type(), rejected.code(), rejected.getMessage());
            tx.executeWithoutResult(s -> {
                insert(event, rawBody);
                markProcessed(event.id(), "REJECTED: " + rejected.code());
            });
            return Ack.REJECTED;
        }
    }

    private Ack apply(WebhookEvent event) {
        var d = event.data();
        switch (event.type()) {
            case WebhookEvent.PAYMENT_CAPTURED -> settlement.applyCapture(d.orderId(), d.paymentId(), d.amountPaise());
            case WebhookEvent.PAYMENT_FAILED -> settlement.applyFailure(d.orderId(), d.reason());
            case WebhookEvent.REFUND_PROCESSED -> settlement.applyRefundProcessed(d.paymentId(), d.refundId());
            default -> {
                return Ack.IGNORED; // unknown types are acknowledged: providers add event types over time
            }
        }
        return Ack.PROCESSED;
    }

    /** @return false if this event id was already recorded (duplicate delivery) */
    private boolean insert(WebhookEvent event, String rawBody) {
        return jdbc.update("""
                INSERT INTO payment_webhook_events (provider_event_id, event_type, provider_order_id, payload, received_at)
                VALUES (:id, :type, :orderId, CAST(:payload AS jsonb), :now)
                ON CONFLICT (provider_event_id) DO NOTHING
                """, new MapSqlParameterSource("id", event.id()).addValue("type", event.type())
                .addValue("orderId", event.data() == null ? null : event.data().orderId())
                .addValue("payload", rawBody).addValue("now", Timestamp.from(clock.instant()))) == 1;
    }

    private void markProcessed(String eventId, String outcome) {
        jdbc.update("UPDATE payment_webhook_events SET processed_at = :now, outcome = :outcome WHERE provider_event_id = :id",
                new MapSqlParameterSource("id", eventId).addValue("outcome", outcome)
                        .addValue("now", Timestamp.from(clock.instant())));
    }

    private WebhookEvent parse(String rawBody) {
        try {
            WebhookEvent event = json.readValue(rawBody, WebhookEvent.class);
            if (event.id() == null || event.type() == null || event.data() == null) {
                throw new ApiException(ErrorCode.INVALID_WEBHOOK, "Missing id, type or data");
            }
            return event;
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.INVALID_WEBHOOK, "Malformed body");
        }
    }
}
