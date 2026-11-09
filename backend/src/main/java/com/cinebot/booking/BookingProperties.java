package com.cinebot.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cinebot.booking")
public record BookingProperties(
        @NotNull Duration lockTtl,
        @Min(1) @Max(10) int maxSeatsPerLock,
        @NotNull Duration paymentGrace,
        @NotNull Duration cancellationCutoff,
        @NotNull Sweeper sweeper) {

    public record Sweeper(boolean enabled, @NotNull Duration interval, @Min(1) @Max(1000) int batchSize) {
    }
}
