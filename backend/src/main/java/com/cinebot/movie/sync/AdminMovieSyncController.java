package com.cinebot.movie.sync;

import com.cinebot.movie.sync.dto.MovieSyncRunResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only: enforced by the /api/v1/admin/** rule in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/movies")
public class AdminMovieSyncController {

    private final MovieSyncService syncService;

    public AdminMovieSyncController(MovieSyncService syncService) {
        this.syncService = syncService;
    }

    /** Synchronous (~seconds for 40 movies): simplest to reason about for an admin action. */
    @PostMapping("/sync")
    public MovieSyncRunResponse sync() {
        return syncService.syncNowPlaying(SyncTrigger.MANUAL);
    }

    @GetMapping("/sync-runs")
    public List<MovieSyncRunResponse> recentRuns() {
        return syncService.recentRuns();
    }
}
