package com.cinebot.movie.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Query parameters of GET /api/v1/movies/search. */
public record MovieSearchQuery(
        @NotBlank @Size(min = 2, max = 100) String q,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size) {

    public MovieSearchQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
