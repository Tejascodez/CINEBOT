package com.cinebot.movie.tmdb;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cinebot.tmdb")
public record TmdbProperties(
        @NotBlank String baseUrl,
        @NotBlank String imageBaseUrl,
        String readAccessToken,
        String apiKey,
        @NotBlank String region,
        @NotBlank String language,
        Sync sync) {

    public TmdbProperties {
        sync = sync == null ? new Sync(2, "-") : sync;
    }

    public boolean hasReadAccessToken() {
        return readAccessToken != null && !readAccessToken.isBlank();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isConfigured() {
        return hasReadAccessToken() || hasApiKey();
    }

    public record Sync(@Min(1) @Max(10) int pages, String cron) {
    }
}
