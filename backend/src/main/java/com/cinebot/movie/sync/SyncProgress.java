package com.cinebot.movie.sync;

/** Counters accumulated while a sync runs; immutable so partial progress can be recorded safely on failure. */
record SyncProgress(int genres, int movies, int skipped) {

    static final SyncProgress NONE = new SyncProgress(0, 0, 0);

    SyncProgress withGenres(int count) {
        return new SyncProgress(count, movies, skipped);
    }

    SyncProgress plus(int moreMovies, int moreSkipped) {
        return new SyncProgress(genres, movies + moreMovies, skipped + moreSkipped);
    }
}
