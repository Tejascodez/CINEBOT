package com.cinebot.payment.dto;

import com.cinebot.payment.PaymentStatus;
import java.time.OffsetDateTime;

/** checkoutUrl is the frontend route of the (simulated) provider checkout page for this order. */
public record PaymentOrderResponse(Long paymentId, Long bookingId, String provider, String orderId, long amountPaise,
                                   String currency, PaymentStatus status, OffsetDateTime expiresAt,
                                   String checkoutUrl) {
}
