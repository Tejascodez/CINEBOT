-- V2: movie catalog (metadata imported from TMDB, served from our DB)

-- Trigram matching for typo-tolerant title search ("intersteller" -> "Interstellar").
-- pg_trgm is a trusted extension (PG13+), so the DB owner can create it; available on RDS/Cloud SQL too.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE genres (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tmdb_genre_id INT,
    name          VARCHAR(50) NOT NULL,
    CONSTRAINT uk_genres_tmdb_genre_id UNIQUE (tmdb_genre_id),
    CONSTRAINT uk_genres_name          UNIQUE (name)
);

CREATE TABLE movies (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tmdb_id           BIGINT       NOT NULL,
    title             VARCHAR(255) NOT NULL,
    original_title    VARCHAR(255),
    overview          TEXT,
    original_language VARCHAR(10),
    release_date      DATE,
    runtime_minutes   INT,
    poster_path       VARCHAR(255),
    backdrop_path     VARCHAR(255),
    vote_average      NUMERIC(3, 1),
    popularity        NUMERIC(12, 3) NOT NULL DEFAULT 0,
    synced_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- the natural key of an import: re-syncing updates the row instead of duplicating it
    CONSTRAINT uk_movies_tmdb_id   UNIQUE (tmdb_id),
    CONSTRAINT ck_movies_runtime   CHECK (runtime_minutes IS NULL OR runtime_minutes BETWEEN 1 AND 600),
    CONSTRAINT ck_movies_vote      CHECK (vote_average IS NULL OR vote_average BETWEEN 0 AND 10),
    CONSTRAINT ck_movies_language  CHECK (original_language IS NULL OR original_language = lower(original_language))
);

-- Serves both "lower(title) LIKE '%dark kn%'" and the word-similarity operator "<%".
CREATE INDEX idx_movies_title_trgm  ON movies USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_movies_language    ON movies (original_language);
CREATE INDEX idx_movies_popularity  ON movies (popularity DESC, id);
CREATE INDEX idx_movies_release     ON movies (release_date DESC, id);

CREATE TABLE movie_genres (
    movie_id BIGINT NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    genre_id BIGINT NOT NULL REFERENCES genres (id),
    PRIMARY KEY (movie_id, genre_id)
);
-- PK covers lookups by movie; this covers "movies in genre X".
CREATE INDEX idx_movie_genres_genre ON movie_genres (genre_id);

CREATE TABLE movie_sync_runs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status          VARCHAR(20) NOT NULL,
    triggered_by    VARCHAR(20) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    genres_upserted INT         NOT NULL DEFAULT 0,
    movies_upserted INT         NOT NULL DEFAULT 0,
    movies_skipped  INT         NOT NULL DEFAULT 0,
    error           TEXT,
    CONSTRAINT ck_movie_sync_runs_status  CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_movie_sync_runs_trigger CHECK (triggered_by IN ('MANUAL', 'SCHEDULED'))
);

-- At most one RUNNING sync, across all app instances: a DB-enforced singleton.
CREATE UNIQUE INDEX uk_movie_sync_runs_one_running ON movie_sync_runs ((status)) WHERE status = 'RUNNING';
CREATE INDEX idx_movie_sync_runs_started ON movie_sync_runs (started_at DESC);
