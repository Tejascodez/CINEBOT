package com.cinebot.show.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * GET /api/v1/shows?movieId=1&city=Chennai&date=2026-11-05&from=20:00
 * date/from/to are local business time (IST); date defaults to today.
 */
public record ShowSearchQuery(
        @Positive Long movieId,
        @Positive Long theatreId,
        @Size(max = 100) String city,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime to) {

    /** Unscoped queries would scan every show in the country. */
    @AssertTrue(message = "at least one of movieId, theatreId or city is required")
    public boolean isScoped() {
        return movieId != null || theatreId != null || (city != null && !city.isBlank());
    }

    @AssertTrue(message = "from must be before to")
    public boolean isTimeRangeValid() {
        return from == null || to == null || from.isBefore(to);
    }
}
