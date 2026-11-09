package com.cinebot.payment.simulator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cinebot.payment.simulator")
public record SimulatorProperties(
        @NotBlank String keySecret,
        @NotBlank String webhookSecret,
        boolean checkoutEnabled,
        @NotNull Delivery webhookDelivery,
        String webhookUrl,
        @NotNull Duration webhookDelay,
        boolean duplicateWebhooks,
        @NotNull Duration refundProcessingDelay) {

    public enum Delivery { HTTP, NONE }
}
