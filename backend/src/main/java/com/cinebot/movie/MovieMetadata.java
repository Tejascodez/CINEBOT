package com.cinebot.movie;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Provider-neutral movie metadata. The TMDB client maps its JSON into this, so the entity does not depend
 * on TMDB's wire format.
 */
public record MovieMetadata(
        long tmdbId,
        String title,
        String originalTitle,
        String overview,
        String originalLanguage,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        String posterPath,
        String backdropPath,
        BigDecimal voteAverage,
        BigDecimal popularity,
        java.util.List<GenreRef> genres) {

    public record GenreRef(int tmdbGenreId, String name) {
    }
}
