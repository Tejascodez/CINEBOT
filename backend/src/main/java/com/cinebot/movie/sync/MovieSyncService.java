package com.cinebot.movie.sync;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.movie.MovieMetadata;
import com.cinebot.movie.sync.dto.MovieSyncRunResponse;
import com.cinebot.movie.tmdb.TmdbClient;
import com.cinebot.movie.tmdb.TmdbException;
import com.cinebot.movie.tmdb.TmdbProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Imports "now playing" movies from TMDB into PostgreSQL.
 *
 * <p>Deliberately NOT @Transactional: it makes many slow HTTP calls, and holding a DB connection + transaction
 * open across network I/O would exhaust the pool and keep locks for seconds. Instead: fetch over HTTP outside any
 * transaction, then write each page in its own short transaction (MovieCatalogWriter).
 */
@Service
public class MovieSyncService {

    private static final Logger log = LoggerFactory.getLogger(MovieSyncService.class);

    private final TmdbClient tmdb;
    private final MovieSyncRunRecorder recorder;
    private final MovieCatalogWriter writer;
    private final MovieSyncRunRepository runs;
    private final TmdbProperties properties;
    private final Clock clock;

    public MovieSyncService(TmdbClient tmdb, MovieSyncRunRecorder recorder, MovieCatalogWriter writer,
                            MovieSyncRunRepository runs, TmdbProperties properties, Clock clock) {
        this.tmdb = tmdb;
        this.recorder = recorder;
        this.writer = writer;
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    public MovieSyncRunResponse syncNowPlaying(SyncTrigger trigger) {
        if (!tmdb.isConfigured()) {
            throw new ApiException(ErrorCode.TMDB_NOT_CONFIGURED,
                    "Set TMDB_READ_ACCESS_TOKEN or TMDB_API_KEY to enable movie sync");
        }
        Long runId = recorder.start(trigger);
        SyncProgress progress = SyncProgress.NONE;
        try {
            progress = progress.withGenres(writer.upsertGenres(tmdb.fetchMovieGenres()));

            Set<Long> seen = new LinkedHashSet<>(); // TMDB pages can shift while we read them -> duplicates
            for (int page = 1; page <= properties.sync().pages(); page++) {
                TmdbClient.NowPlayingPage nowPlaying = tmdb.fetchNowPlaying(page);
                List<MovieMetadata> batch = new ArrayList<>();
                int skipped = 0;
                for (Long tmdbId : nowPlaying.tmdbIds()) {
                    if (!seen.add(tmdbId)) {
                        continue;
                    }
                    try {
                        batch.add(tmdb.fetchMovieDetails(tmdbId));
                    } catch (TmdbException e) {
                        // One bad movie (removed, incomplete) must not abort the whole import.
                        log.warn("Skipping TMDB movie {}: {}", tmdbId, e.getMessage());
                        skipped++;
                    }
                }
                progress = progress.plus(writer.upsertMovies(batch, clock.instant()), skipped);
                if (page >= nowPlaying.totalPages()) {
                    break;
                }
            }
            log.info("Movie sync {} succeeded: {}", runId, progress);
            return MovieSyncRunResponse.from(recorder.finish(runId, SyncStatus.SUCCEEDED, progress, null));
        } catch (TmdbException e) {
            recorder.finish(runId, SyncStatus.FAILED, progress, e.getMessage());
            throw new ApiException(ErrorCode.TMDB_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Movie sync {} failed unexpectedly", runId, e);
            recorder.finish(runId, SyncStatus.FAILED, progress, "Unexpected error: " + e.getClass().getSimpleName());
            throw e;
        }
    }

    public List<MovieSyncRunResponse> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc().stream().map(MovieSyncRunResponse::from).toList();
    }
}
