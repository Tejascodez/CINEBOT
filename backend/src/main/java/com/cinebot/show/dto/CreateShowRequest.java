package com.cinebot.show.dto;

import com.cinebot.screen.SeatType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * startTime must carry an offset ("2026-11-05T19:30:00+05:30"): a bare local time is ambiguous.
 * prices are in paise per seat type, e.g. {"REGULAR": 20000, "PREMIUM": 30000}.
 */
public record CreateShowRequest(
        @NotNull @Positive Long movieId,
        @NotNull @Positive Long screenId,
        @NotNull OffsetDateTime startTime,
        @Pattern(regexp = "^[a-z]{2}$", message = "must be a 2-letter ISO 639-1 code") String language,
        @Pattern(regexp = "^(2D|3D)$", message = "must be 2D or 3D") String format,
        @NotEmpty Map<SeatType, @NotNull @Positive @Max(1_000_000) Long> prices) {
}
