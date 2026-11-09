package com.cinebot.movie.dto;

import com.cinebot.movie.MovieSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Query parameters of GET /api/v1/movies, bound and validated as one object. */
public record MovieListQuery(
        @Positive Long genreId,
        @Size(max = 100) String city,
        @Pattern(regexp = "^[a-z]{2}$", message = "must be a 2-letter ISO 639-1 code, e.g. en, hi, ta") String language,
        MovieSort sort,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size) {

    public MovieListQuery {
        sort = sort == null ? MovieSort.POPULARITY : sort;
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
