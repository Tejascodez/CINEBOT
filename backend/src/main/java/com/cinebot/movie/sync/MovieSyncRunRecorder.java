package com.cinebot.movie.sync;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions around the sync run row. A separate bean from MovieSyncService because Spring's
 * @Transactional works through a proxy: a call from one method to another inside the same class bypasses it.
 */
@Component
class MovieSyncRunRecorder {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final MovieSyncRunRepository runs;
    private final Clock clock;

    MovieSyncRunRecorder(MovieSyncRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * Claims the single RUNNING slot. The partial unique index uk_movie_sync_runs_one_running makes a second
     * concurrent insert fail (on any app instance), which is translated to 409.
     */
    @Transactional
    Long start(SyncTrigger trigger) {
        Instant now = clock.instant();
        runs.abandonStale(now.minus(STALE_AFTER), now);
        try {
            return runs.saveAndFlush(MovieSyncRun.start(trigger, now)).getId();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.SYNC_ALREADY_RUNNING);
        }
    }

    @Transactional
    MovieSyncRun finish(Long runId, SyncStatus outcome, SyncProgress progress, String error) {
        MovieSyncRun run = runs.findById(runId).orElseThrow();
        run.finish(outcome, progress, error, clock.instant());
        return run;
    }
}
