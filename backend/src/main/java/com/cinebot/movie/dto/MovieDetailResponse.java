package com.cinebot.movie.dto;

import com.cinebot.genre.dto.GenreResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MovieDetailResponse(
        Long id,
        Long tmdbId,
        String title,
        String originalTitle,
        String overview,
        String language,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        String posterUrl,
        String backdropUrl,
        BigDecimal rating,
        List<GenreResponse> genres) {
}
