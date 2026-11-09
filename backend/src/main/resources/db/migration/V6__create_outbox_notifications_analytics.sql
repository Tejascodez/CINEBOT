-- V6: transactional outbox, per-handler de-duplication, notifications, sales analytics

-- Written in the SAME transaction as the state change it describes: either both commit or neither does.
-- This solves the dual-write problem (DB updated but event lost, or event sent for a change that rolled back).
CREATE TABLE outbox_events (
    id             UUID        PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   VARCHAR(40) NOT NULL,
    event_type     VARCHAR(60) NOT NULL,
    payload        JSONB       NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts       INT         NOT NULL DEFAULT 0,
    available_at   TIMESTAMPTZ NOT NULL,         -- not before: implements retry backoff
    locked_until   TIMESTAMPTZ,                  -- lease held by a relay worker; expires if the worker dies
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    processed_at   TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'DONE', 'DEAD'))
);
-- Relay poll: due PENDING events in order. Partial index stays tiny because DONE rows are excluded.
CREATE INDEX idx_outbox_due ON outbox_events (available_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id, created_at);

-- "Handler X has applied event Y". Inserted in the same transaction as the handler's own effect, so a
-- redelivered event is skipped: exactly-once EFFECT on top of at-least-once DELIVERY.
CREATE TABLE processed_events (
    handler      VARCHAR(60) NOT NULL,
    event_id     UUID        NOT NULL REFERENCES outbox_events (id),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (handler, event_id)
);

CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    event_id   UUID         NOT NULL REFERENCES outbox_events (id),
    kind       VARCHAR(40)  NOT NULL,
    channel    VARCHAR(10)  NOT NULL,
    recipient  VARCHAR(255) NOT NULL,
    subject    VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_notifications_event_channel UNIQUE (event_id, channel)
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC, id DESC);

-- Running counters per show. Increments are NOT idempotent by nature, which is exactly why
-- processed_events matters here: a duplicate delivery must not count a sale twice.
CREATE TABLE show_sales_stats (
    show_id            BIGINT      PRIMARY KEY REFERENCES shows (id),
    bookings_confirmed INT         NOT NULL DEFAULT 0,
    seats_sold         INT         NOT NULL DEFAULT 0,
    revenue_paise      BIGINT      NOT NULL DEFAULT 0,
    refunds            INT         NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL
);
