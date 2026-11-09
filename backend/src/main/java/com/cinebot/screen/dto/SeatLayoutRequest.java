package com.cinebot.screen.dto;

import com.cinebot.screen.SeatType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Compact layout: rows front-to-back, each with a seat count and type, e.g.
 * {"rows":[{"label":"A","seats":12,"type":"REGULAR"}, ... ,{"label":"J","seats":8,"type":"RECLINER"}]}
 * Seats are numbered 1..n within the row.
 */
public record SeatLayoutRequest(@NotEmpty @Size(max = 40) List<@Valid @NotNull Row> rows) {

    public record Row(
            @NotNull @Pattern(regexp = "^[A-Z]{1,2}$", message = "must be 1-2 uppercase letters") String label,
            @Min(1) @Max(50) int seats,
            @NotNull SeatType type) {
    }
}
