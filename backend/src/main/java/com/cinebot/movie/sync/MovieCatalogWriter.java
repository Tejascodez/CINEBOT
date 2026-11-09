package com.cinebot.movie.sync;

import com.cinebot.genre.Genre;
import com.cinebot.genre.GenreService;
import com.cinebot.movie.Movie;
import com.cinebot.movie.MovieMetadata;
import com.cinebot.movie.MovieRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes already-fetched TMDB data. One transaction per batch (page): short DB locks, and a failure on page 3
 * keeps pages 1-2. That is safe because every write is an upsert keyed by TMDB id, so re-running is harmless.
 */
@Component
class MovieCatalogWriter {

    private final MovieRepository movies;
    private final GenreService genreService;

    MovieCatalogWriter(MovieRepository movies, GenreService genreService) {
        this.movies = movies;
        this.genreService = genreService;
    }

    @Transactional
    int upsertGenres(List<MovieMetadata.GenreRef> genres) {
        genres.forEach(g -> genreService.upsert(g.tmdbGenreId(), g.name()));
        return genres.size();
    }

    @Transactional
    int upsertMovies(List<MovieMetadata> batch, Instant syncedAt) {
        Map<Integer, Genre> genreCache = new HashMap<>();
        for (MovieMetadata metadata : batch) {
            List<Genre> genres = metadata.genres().stream()
                    .map(ref -> genreCache.computeIfAbsent(ref.tmdbGenreId(),
                            id -> genreService.upsert(id, ref.name())))
                    .toList();
            Movie movie = movies.findByTmdbId(metadata.tmdbId())
                    .orElseGet(() -> new Movie(metadata.tmdbId()));
            movie.applyMetadata(metadata, genres, syncedAt);
            movies.save(movie);
        }
        return batch.size();
    }
}
