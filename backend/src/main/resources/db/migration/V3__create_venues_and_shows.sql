-- V3: theatres, screens, physical seats, shows, per-show seat inventory

-- Lets a GiST index combine "=" on a plain column (screen_id) with "&&" on a range, for the exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE theatres (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(150) NOT NULL,
    brand      VARCHAR(50),
    city       VARCHAR(100) NOT NULL,
    address    VARCHAR(300) NOT NULL,
    latitude   NUMERIC(9, 6),
    longitude  NUMERIC(9, 6),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_theatres_city      CHECK (btrim(city) <> ''),
    CONSTRAINT ck_theatres_latitude  CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_theatres_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
-- No two theatres with the same name in a city (case-insensitive). Leading lower(city) also serves city lookups.
CREATE UNIQUE INDEX uk_theatres_city_name ON theatres (lower(city), lower(name));

CREATE TABLE screens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    theatre_id  BIGINT      NOT NULL REFERENCES theatres (id),
    name        VARCHAR(50) NOT NULL,
    screen_type VARCHAR(10) NOT NULL,
    total_seats INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_screens_theatre_name UNIQUE (theatre_id, name),
    CONSTRAINT ck_screens_type         CHECK (screen_type IN ('STANDARD', 'IMAX', 'DOLBY')),
    CONSTRAINT ck_screens_total_seats  CHECK (total_seats >= 0)
);

-- Physical seats: change only when an admin redesigns the screen; never mutated by bookings.
CREATE TABLE seats (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    screen_id   BIGINT      NOT NULL REFERENCES screens (id),
    row_label   VARCHAR(2)  NOT NULL,
    row_index   INT         NOT NULL,
    seat_number INT         NOT NULL,
    seat_type   VARCHAR(10) NOT NULL,
    CONSTRAINT uk_seats_screen_row_number UNIQUE (screen_id, row_label, seat_number),
    CONSTRAINT ck_seats_type              CHECK (seat_type IN ('REGULAR', 'PREMIUM', 'RECLINER')),
    CONSTRAINT ck_seats_number            CHECK (seat_number BETWEEN 1 AND 100),
    CONSTRAINT ck_seats_row_index         CHECK (row_index >= 0)
);

-- end_time = start + runtime + turnaround buffer (ads, cleaning): the interval the screen is occupied.
CREATE TABLE shows (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movie_id   BIGINT      NOT NULL REFERENCES movies (id),
    screen_id  BIGINT      NOT NULL REFERENCES screens (id),
    start_time TIMESTAMPTZ NOT NULL,
    end_time   TIMESTAMPTZ NOT NULL,
    language   VARCHAR(10) NOT NULL,
    format     VARCHAR(5)  NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_shows_time   CHECK (end_time > start_time),
    CONSTRAINT ck_shows_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    CONSTRAINT ck_shows_format CHECK (format IN ('2D', '3D')),
    -- The DB, not application code, guarantees that two scheduled shows never overlap on one screen.
    -- Race-safe: two concurrent inserts cannot both pass, unlike a "SELECT overlapping; then INSERT" check.
    -- Half-open range [start, end): a show may start exactly when the previous one ends.
    CONSTRAINT ex_shows_screen_no_overlap
        EXCLUDE USING gist (screen_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
        WHERE (status = 'SCHEDULED')
);
CREATE INDEX idx_shows_movie_start ON shows (movie_id, start_time);
CREATE INDEX idx_shows_start       ON shows (start_time);

-- Per-show inventory: one row per physical seat per show. The unit that gets locked and booked (Step 4).
CREATE TABLE show_seats (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id     BIGINT      NOT NULL REFERENCES shows (id),
    seat_id     BIGINT      NOT NULL REFERENCES seats (id),
    price_paise BIGINT      NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    version     BIGINT      NOT NULL DEFAULT 0,
    -- Core double-booking guard: exactly one inventory row per seat per show, so a seat can hold one owner.
    CONSTRAINT uk_show_seats_show_seat UNIQUE (show_id, seat_id),
    CONSTRAINT ck_show_seats_price     CHECK (price_paise > 0),
    CONSTRAINT ck_show_seats_status    CHECK (status IN ('AVAILABLE', 'LOCKED', 'BOOKED', 'BLOCKED'))
);
-- Seat map and "available seats" counts.
CREATE INDEX idx_show_seats_show_status ON show_seats (show_id, status);
-- Supports the FK check when a screen's seats are deleted during a layout redesign.
CREATE INDEX idx_show_seats_seat ON show_seats (seat_id);
