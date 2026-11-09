package com.cinebot.payment.gateway;

import java.time.Instant;

public record GatewayOrder(String orderId, long amountPaise, String currency, Instant expiresAt) {
}
