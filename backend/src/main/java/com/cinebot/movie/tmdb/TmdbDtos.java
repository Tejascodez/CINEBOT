package com.cinebot.movie.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * TMDB wire format (snake_case JSON). Kept package-private to the client: nothing outside this package
 * sees TMDB's shapes. Unknown fields are ignored so TMDB adding fields never breaks us.
 */
final class TmdbDtos {

    private TmdbDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenreList(List<Genre> genres) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Genre(int id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record MoviePage(int page, int totalPages, List<MovieListItem> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovieListItem(long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record MovieDetails(
            long id,
            String title,
            String originalTitle,
            String overview,
            String originalLanguage,
            String releaseDate,   // TMDB sometimes sends "" here, so it is parsed leniently, not bound as LocalDate
            Integer runtime,
            String posterPath,
            String backdropPath,
            Double voteAverage,
            Double popularity,
            List<Genre> genres) {
    }
}
