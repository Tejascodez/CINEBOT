package com.cinebot.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cinebot.outbox")
public record OutboxProperties(@NotNull Relay relay, @Min(1) int batchSize, @NotNull Duration lease,
                               @Min(1) int maxAttempts, @NotNull Duration maxBackoff) {

    public record Relay(boolean enabled, @NotNull Duration interval) {
    }
}
