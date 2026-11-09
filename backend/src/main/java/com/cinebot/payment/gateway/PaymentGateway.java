package com.cinebot.payment.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything the booking engine needs from a payment provider. The simulator implements it today; a real
 * provider (e.g. Razorpay) would be one more implementation, with no change to payment or booking logic.
 * Implementations make (or simulate) remote calls: callers must never invoke them inside a DB transaction.
 */
public interface PaymentGateway {

    String provider();

    GatewayOrder createOrder(String receipt, long amountPaise, String currency, Instant expiresAt);

    Optional<GatewayPayment> fetchPayment(String paymentId);

    /** All payment attempts on an order: the reconciler's view of "what really happened". */
    List<GatewayPayment> fetchPaymentsForOrder(String orderId);

    /** Idempotent per {@code idempotencyKey}: retrying never refunds twice. */
    GatewayRefund refund(String paymentId, long amountPaise, String idempotencyKey);

    Optional<GatewayRefund> fetchRefund(String refundId);

    /** Signature the checkout returns to the browser over orderId|paymentId. */
    boolean verifyCheckoutSignature(String orderId, String paymentId, String signature);

    /** Signature header over the exact raw webhook body. */
    boolean verifyWebhookSignature(String rawBody, String signature);
}
