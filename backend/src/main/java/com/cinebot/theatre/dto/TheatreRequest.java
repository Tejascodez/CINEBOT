package com.cinebot.theatre.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TheatreRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 50) String brand,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 300) String address,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        Boolean active) {
}
