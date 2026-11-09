package com.cinebot.movie.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MovieSummaryResponse(
        Long id,
        String title,
        String posterUrl,
        LocalDate releaseDate,
        String language,
        Integer runtimeMinutes,
        BigDecimal rating,
        List<String> genres) {
}
