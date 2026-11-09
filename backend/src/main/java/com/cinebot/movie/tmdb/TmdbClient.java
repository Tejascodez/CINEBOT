package com.cinebot.movie.tmdb;

import com.cinebot.movie.MovieMetadata;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Thin TMDB v3 client. Returns provider-neutral types (MovieMetadata) and throws only TmdbException.
 * Timeouts come from spring.http.client.* applied to the auto-configured RestClient.Builder.
 */
@Component
public class TmdbClient {

    private final RestClient http;
    private final TmdbProperties properties;

    public TmdbClient(RestClient.Builder builder, TmdbProperties properties) {
        this.properties = properties;
        RestClient.Builder configured = builder.baseUrl(properties.baseUrl());
        if (properties.hasReadAccessToken()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readAccessToken());
        }
        this.http = configured.build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public List<MovieMetadata.GenreRef> fetchMovieGenres() {
        TmdbDtos.GenreList list = get(uri -> uri.path("/genre/movie/list")
                .queryParam("language", properties.language()).build(), TmdbDtos.GenreList.class);
        if (list == null || list.genres() == null) {
            throw new TmdbException("Empty genre list from TMDB");
        }
        return list.genres().stream().map(g -> new MovieMetadata.GenreRef(g.id(), g.name())).toList();
    }

    public NowPlayingPage fetchNowPlaying(int page) {
        TmdbDtos.MoviePage result = get(uri -> uri.path("/movie/now_playing")
                .queryParam("language", properties.language())
                .queryParam("region", properties.region())
                .queryParam("page", page).build(), TmdbDtos.MoviePage.class);
        if (result == null || result.results() == null) {
            throw new TmdbException("Empty now_playing page " + page + " from TMDB");
        }
        return new NowPlayingPage(result.page(), result.totalPages(),
                result.results().stream().map(TmdbDtos.MovieListItem::id).toList());
    }

    /** The list endpoint lacks runtime (needed later to compute show end times), hence one details call per movie. */
    public MovieMetadata fetchMovieDetails(long tmdbId) {
        TmdbDtos.MovieDetails d = get(uri -> uri.path("/movie/{id}")
                .queryParam("language", properties.language())
                .build(tmdbId), TmdbDtos.MovieDetails.class);
        if (d == null || d.title() == null || d.title().isBlank()) {
            throw new TmdbException("Incomplete details for TMDB movie " + tmdbId);
        }
        return new MovieMetadata(
                d.id(), d.title(), d.originalTitle(), d.overview(),
                d.originalLanguage() == null ? null : d.originalLanguage().toLowerCase(Locale.ROOT),
                parseDate(d.releaseDate()),
                d.runtime() == null || d.runtime() <= 0 ? null : d.runtime(),
                d.posterPath(), d.backdropPath(),
                d.voteAverage() == null ? null : BigDecimal.valueOf(d.voteAverage()).setScale(1, RoundingMode.HALF_UP),
                d.popularity() == null ? null : BigDecimal.valueOf(d.popularity()).setScale(3, RoundingMode.HALF_UP),
                d.genres() == null ? List.of()
                        : d.genres().stream().map(g -> new MovieMetadata.GenreRef(g.id(), g.name())).toList());
    }

    private <T> T get(Function<UriBuilder, URI> uriSpec, Class<T> type) {
        try {
            return http.get()
                    .uri(uri -> {
                        if (!properties.hasReadAccessToken() && properties.hasApiKey()) {
                            uri.queryParam("api_key", properties.apiKey());
                        }
                        return uriSpec.apply(uri);
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        // Path only: the full URI may contain the api_key.
                        throw new TmdbException("TMDB returned " + response.getStatusCode().value()
                                + " for " + request.getURI().getPath());
                    })
                    .body(type);
        } catch (TmdbException e) {
            throw e;
        } catch (RestClientException e) {
            throw new TmdbException("TMDB call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public record NowPlayingPage(int page, int totalPages, List<Long> tmdbIds) {
    }
}
