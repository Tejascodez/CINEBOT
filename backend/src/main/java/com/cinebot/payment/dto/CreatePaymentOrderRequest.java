package com.cinebot.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePaymentOrderRequest(@NotNull @Positive Long bookingId) {
}
