package com.cinebot.theatre.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** City is required: a nationwide unfiltered theatre list is never what a client needs. */
public record TheatreListQuery(
        @NotBlank @Size(max = 100) String city,
        @Size(max = 50) String brand,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size) {

    public TheatreListQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
