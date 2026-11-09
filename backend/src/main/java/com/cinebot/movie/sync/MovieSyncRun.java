package com.cinebot.movie.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Audit + mutual-exclusion record for one TMDB import. */
@Entity
@Table(name = "movie_sync_runs")
public class MovieSyncRun {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false)
    private SyncTrigger triggeredBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "genres_upserted", nullable = false)
    private int genresUpserted;

    @Column(name = "movies_upserted", nullable = false)
    private int moviesUpserted;

    @Column(name = "movies_skipped", nullable = false)
    private int moviesSkipped;

    @Column(columnDefinition = "text")
    private String error;

    protected MovieSyncRun() {
        // JPA
    }

    static MovieSyncRun start(SyncTrigger trigger, Instant now) {
        MovieSyncRun run = new MovieSyncRun();
        run.status = SyncStatus.RUNNING;
        run.triggeredBy = trigger;
        run.startedAt = now;
        return run;
    }

    void finish(SyncStatus outcome, SyncProgress progress, String errorMessage, Instant now) {
        if (status != SyncStatus.RUNNING) {
            throw new IllegalStateException("Sync run " + id + " already finished as " + status);
        }
        status = outcome;
        genresUpserted = progress.genres();
        moviesUpserted = progress.movies();
        moviesSkipped = progress.skipped();
        error = errorMessage == null || errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
        finishedAt = now;
    }

    public Long getId() {
        return id;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public SyncTrigger getTriggeredBy() {
        return triggeredBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getGenresUpserted() {
        return genresUpserted;
    }

    public int getMoviesUpserted() {
        return moviesUpserted;
    }

    public int getMoviesSkipped() {
        return moviesSkipped;
    }

    public String getError() {
        return error;
    }
}
