package com.cinebot.payment.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Provider webhook contract. Types: payment.captured, payment.failed, refund.processed.
 * {@code id} is unique per event and is our de-duplication key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(String id, String type, Instant createdAt, Data data) {

    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_PROCESSED = "refund.processed";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String orderId, String paymentId, String refundId, Long amountPaise, String currency,
                       String reason) {
    }
}
