package com.cinebot.movie.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.movie.MovieMetadata;
import com.cinebot.movie.MovieMetadata.GenreRef;
import com.cinebot.movie.tmdb.TmdbClient;
import com.cinebot.movie.tmdb.TmdbException;
import com.cinebot.support.IntegrationTest;
import com.cinebot.user.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** TMDB import against a mocked TmdbClient and a real Postgres. */
class MovieSyncIT extends IntegrationTest {

    private static final GenreRef COMEDY = new GenreRef(35, "Comedy");
    private static final GenreRef DRAMA = new GenreRef(18, "Drama");
    private static final GenreRef SCIFI = new GenreRef(878, "Science Fiction");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MovieSyncService syncService;

    private String admin;

    @BeforeEach
    void setUp() {
        admin = bearer(Role.ADMIN);
        when(tmdbClient.isConfigured()).thenReturn(true);
        when(tmdbClient.fetchMovieGenres()).thenReturn(List.of(COMEDY, DRAMA, SCIFI));
        // TMDB repeats id 1 on the page: must be imported once.
        when(tmdbClient.fetchNowPlaying(1)).thenReturn(new TmdbClient.NowPlayingPage(1, 1, List.of(1L, 2L, 1L)));
        when(tmdbClient.fetchMovieDetails(1L)).thenReturn(details(1, "Interstellar", SCIFI, DRAMA));
        when(tmdbClient.fetchMovieDetails(2L)).thenReturn(details(2, "3 Idiots", COMEDY));
    }

    @Test
    void importsGenresAndMovies() throws Exception {
        mvc.perform(post("/api/v1/admin/movies/sync").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.triggeredBy").value("MANUAL"))
                .andExpect(jsonPath("$.genresUpserted").value(3))
                .andExpect(jsonPath("$.moviesUpserted").value(2))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        assertThat(count("movies")).isEqualTo(2);
        assertThat(count("genres")).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT g.name FROM movie_genres mg JOIN movies m ON m.id = mg.movie_id JOIN genres g ON g.id = mg.genre_id
                WHERE m.tmdb_id = 1 ORDER BY g.name""", String.class)).containsExactly("Drama", "Science Fiction");
        // only 1 page exists (totalPages = 1), even though config allows 2
        verify(tmdbClient, never()).fetchNowPlaying(2);
    }

    @Test
    void reSyncIsIdempotentAndAppliesChanges() {
        syncService.syncNowPlaying(SyncTrigger.MANUAL);
        Long idBefore = jdbc.queryForObject("SELECT id FROM movies WHERE tmdb_id = 1", Long.class);

        // TMDB changed the title and genres since the last import
        when(tmdbClient.fetchMovieDetails(1L)).thenReturn(details(1, "Interstellar (Re-release)", SCIFI));
        syncService.syncNowPlaying(SyncTrigger.MANUAL);

        assertThat(count("movies")).isEqualTo(2);
        assertThat(count("genres")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT id FROM movies WHERE tmdb_id = 1", Long.class)).isEqualTo(idBefore);
        assertThat(jdbc.queryForObject("SELECT title FROM movies WHERE tmdb_id = 1", String.class))
                .isEqualTo("Interstellar (Re-release)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movie_genres mg JOIN movies m ON m.id = mg.movie_id "
                + "WHERE m.tmdb_id = 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void oneBrokenMovieIsSkippedNotFatal() {
        when(tmdbClient.fetchMovieDetails(2L)).thenThrow(new TmdbException("TMDB returned 404 for /3/movie/2"));

        var run = syncService.syncNowPlaying(SyncTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.moviesUpserted()).isEqualTo(1);
        assertThat(run.moviesSkipped()).isEqualTo(1);
    }

    @Test
    void tmdbOutageIsRecordedAsFailedRunAnd502() throws Exception {
        when(tmdbClient.fetchMovieGenres()).thenThrow(new TmdbException("TMDB returned 503 for /3/genre/movie/list"));

        mvc.perform(post("/api/v1/admin/movies/sync").header("Authorization", admin))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TMDB_UNAVAILABLE"));

        mvc.perform(get("/api/v1/admin/movies/sync-runs").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].error").value("TMDB returned 503 for /3/genre/movie/list"));
        // the failed run released the RUNNING slot, so the next sync can start
        doReturn(List.of(COMEDY)).when(tmdbClient).fetchMovieGenres();
        assertThat(syncService.syncNowPlaying(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    void notConfiguredIs503() throws Exception {
        when(tmdbClient.isConfigured()).thenReturn(false);

        mvc.perform(post("/api/v1/admin/movies/sync").header("Authorization", admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TMDB_NOT_CONFIGURED"));
        assertThat(count("movie_sync_runs")).isZero();
    }

    @Test
    void secondConcurrentSyncIsRejected() throws Exception {
        CountDownLatch firstIsInsideSync = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(tmdbClient.fetchMovieGenres()).thenAnswer(inv -> {
            firstIsInsideSync.countDown();
            assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(COMEDY);
        });

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> syncService.syncNowPlaying(SyncTrigger.MANUAL));
        assertThat(firstIsInsideSync.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            syncService.syncNowPlaying(SyncTrigger.SCHEDULED);
            throw new AssertionError("second sync should have been rejected");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING);
        } finally {
            releaseFirst.countDown();
        }
        first.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForList("SELECT status FROM movie_sync_runs", String.class)).containsExactly("SUCCEEDED");
    }

    @Test
    void staleRunningSyncFromCrashedInstanceIsAbandoned() {
        jdbc.update("INSERT INTO movie_sync_runs (status, triggered_by, started_at) "
                + "VALUES ('RUNNING', 'SCHEDULED', now() - interval '2 hours')");

        var run = syncService.syncNowPlaying(SyncTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT error FROM movie_sync_runs WHERE id = 1", String.class))
                .startsWith("Abandoned");
    }

    @Test
    void syncEndpointsAreAdminOnly() throws Exception {
        mvc.perform(post("/api/v1/admin/movies/sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/movies/sync").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden());
        verify(tmdbClient, never()).fetchNowPlaying(anyInt());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static MovieMetadata details(long id, String title, GenreRef... genres) {
        return new MovieMetadata(id, title, title, "Overview of " + title, "en", LocalDate.of(2014, 11, 5), 150,
                "/p" + id + ".jpg", null, new BigDecimal("8.1"), new BigDecimal("100.000"), List.of(genres));
    }
}
