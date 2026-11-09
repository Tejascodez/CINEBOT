package com.cinebot.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyPaymentRequest(@NotBlank @Size(max = 40) String orderId,
                                   @NotBlank @Size(max = 40) String paymentId,
                                   @NotBlank @Size(max = 128) String signature) {
}
