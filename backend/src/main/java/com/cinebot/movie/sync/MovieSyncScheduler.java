package com.cinebot.movie.sync;

import com.cinebot.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional nightly sync. Disabled when cinebot.tmdb.sync.cron is "-" (the default).
 * With several app instances each fires the cron, but the single-RUNNING DB index lets only one proceed.
 */
@Component
class MovieSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MovieSyncScheduler.class);

    private final MovieSyncService syncService;

    MovieSyncScheduler(MovieSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${cinebot.tmdb.sync.cron:-}", zone = "Asia/Kolkata")
    void scheduledSync() {
        try {
            syncService.syncNowPlaying(SyncTrigger.SCHEDULED);
        } catch (ApiException e) {
            log.warn("Scheduled movie sync did not run: {} ({})", e.code(), e.getMessage());
        }
    }
}
