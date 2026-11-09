-- DEV ONLY (loaded via the dev profile's flyway.locations).
-- Intentionally empty: movies and genres come only from TMDB (admin "Sync", POST /api/v1/admin/movies/sync,
-- or TMDB_SYNC_CRON). This used to insert 10 sample movies; it is kept as a no-op so Flyway's history of the
-- repeatable migration still resolves on existing dev databases.
SELECT 1;
