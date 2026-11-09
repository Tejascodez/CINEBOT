package com.cinebot.movie.sync.dto;

import com.cinebot.movie.sync.MovieSyncRun;
import com.cinebot.movie.sync.SyncStatus;
import com.cinebot.movie.sync.SyncTrigger;
import java.time.Instant;

public record MovieSyncRunResponse(
        Long id,
        SyncStatus status,
        SyncTrigger triggeredBy,
        Instant startedAt,
        Instant finishedAt,
        int genresUpserted,
        int moviesUpserted,
        int moviesSkipped,
        String error) {

    public static MovieSyncRunResponse from(MovieSyncRun run) {
        return new MovieSyncRunResponse(run.getId(), run.getStatus(), run.getTriggeredBy(), run.getStartedAt(),
                run.getFinishedAt(), run.getGenresUpserted(), run.getMoviesUpserted(), run.getMoviesSkipped(),
                run.getError());
    }
}
