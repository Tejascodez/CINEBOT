package com.cinebot.booking.lock.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The per-lock maximum (default 10) is a configurable business rule, enforced in the service. */
public record LockSeatsRequest(
        @NotNull @Positive Long showId,
        @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> showSeatIds) {
}
