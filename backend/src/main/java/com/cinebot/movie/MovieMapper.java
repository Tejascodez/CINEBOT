package com.cinebot.movie;

import com.cinebot.genre.Genre;
import com.cinebot.genre.dto.GenreResponse;
import com.cinebot.movie.dto.MovieDetailResponse;
import com.cinebot.movie.dto.MovieSummaryResponse;
import com.cinebot.movie.tmdb.TmdbProperties;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Entity -> DTO. Only image *paths* are stored; full URLs are built here, so changing CDN or image size
 * needs no data migration.
 */
@Component
public class MovieMapper {

    private static final String POSTER_SIZE = "w500";
    private static final String BACKDROP_SIZE = "w1280";

    private final String imageBaseUrl;

    public MovieMapper(TmdbProperties properties) {
        this.imageBaseUrl = properties.imageBaseUrl();
    }

    public MovieSummaryResponse toSummary(Movie movie) {
        return new MovieSummaryResponse(movie.getId(), movie.getTitle(), imageUrl(POSTER_SIZE, movie.getPosterPath()),
                movie.getReleaseDate(), movie.getOriginalLanguage(), movie.getRuntimeMinutes(),
                movie.getVoteAverage(), sortedGenres(movie).stream().map(Genre::getName).toList());
    }

    public MovieDetailResponse toDetail(Movie movie) {
        return new MovieDetailResponse(movie.getId(), movie.getTmdbId(), movie.getTitle(), movie.getOriginalTitle(),
                movie.getOverview(), movie.getOriginalLanguage(), movie.getReleaseDate(), movie.getRuntimeMinutes(),
                imageUrl(POSTER_SIZE, movie.getPosterPath()), imageUrl(BACKDROP_SIZE, movie.getBackdropPath()),
                movie.getVoteAverage(), sortedGenres(movie).stream().map(GenreResponse::from).toList());
    }

    private static List<Genre> sortedGenres(Movie movie) {
        return movie.getGenres().stream().sorted(Comparator.comparing(Genre::getName)).toList();
    }

    private String imageUrl(String size, String path) {
        return path == null || path.isBlank() ? null : imageBaseUrl + "/" + size + path;
    }
}
