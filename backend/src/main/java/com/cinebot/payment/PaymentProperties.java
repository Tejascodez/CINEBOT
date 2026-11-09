package com.cinebot.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cinebot.payment")
public record PaymentProperties(@NotNull String provider, @NotNull Reconciler reconciler, @NotNull Faults faults) {

    public record Reconciler(boolean enabled, @NotNull Duration interval, @NotNull Duration stuckAfter,
                             @Min(1) int batchSize) {
    }

    public record Faults(boolean enabled) {
    }
}
