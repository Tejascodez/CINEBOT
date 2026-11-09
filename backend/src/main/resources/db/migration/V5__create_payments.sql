-- V5: payments, webhook de-duplication, and the simulated payment provider's own state

-- One payment ORDER per booking (provider "order" = one checkout; status tracks its outcome).
CREATE TABLE payments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id          BIGINT      NOT NULL REFERENCES bookings (id),
    provider            VARCHAR(20) NOT NULL,
    provider_order_id   VARCHAR(40) NOT NULL,
    provider_payment_id VARCHAR(40),
    provider_refund_id  VARCHAR(40),
    amount_paise        BIGINT      NOT NULL,
    currency            VARCHAR(3)  NOT NULL,
    status              VARCHAR(20) NOT NULL,
    failure_reason      VARCHAR(200),
    order_expires_at    TIMESTAMPTZ NOT NULL,
    captured_at         TIMESTAMPTZ,
    refund_requested_at TIMESTAMPTZ,
    refunded_at         TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    -- Idempotent order creation: a booking can never get two orders, however many times "Pay" is clicked.
    CONSTRAINT uk_payments_booking  UNIQUE (booking_id),
    CONSTRAINT uk_payments_order    UNIQUE (provider_order_id),
    CONSTRAINT uk_payments_payment  UNIQUE (provider_payment_id),
    CONSTRAINT uk_payments_refund   UNIQUE (provider_refund_id),
    CONSTRAINT ck_payments_amount   CHECK (amount_paise > 0),
    CONSTRAINT ck_payments_status   CHECK (status IN ('CREATED', 'CAPTURED', 'FAILED', 'EXPIRED',
                                                      'REFUND_PENDING', 'REFUNDED')),
    -- money that moved must be traceable to a provider payment id
    CONSTRAINT ck_payments_captured CHECK (status NOT IN ('CAPTURED', 'REFUND_PENDING', 'REFUNDED')
                                           OR provider_payment_id IS NOT NULL)
);
-- Reconciler: unfinished orders and refunds.
CREATE INDEX idx_payments_open ON payments (created_at) WHERE status IN ('CREATED', 'REFUND_PENDING');

-- Every webhook delivery we accepted, keyed by the provider's event id: a re-delivery hits the PK -> no-op.
CREATE TABLE payment_webhook_events (
    provider_event_id VARCHAR(60) PRIMARY KEY,
    event_type        VARCHAR(40) NOT NULL,
    provider_order_id VARCHAR(40),
    payload           JSONB       NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL,
    processed_at      TIMESTAMPTZ,
    outcome           VARCHAR(80)
);

-- ---------------------------------------------------------------------------------------------------------------
-- Simulated provider. In production these tables would not exist: they stand in for the external payment
-- system, which keeps its own durable record of orders and captures (that durability is what makes
-- crash recovery by reconciliation possible).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE sim_orders (
    id           VARCHAR(40) PRIMARY KEY,
    receipt      VARCHAR(40) NOT NULL,
    amount_paise BIGINT      NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    status       VARCHAR(10) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_sim_orders_status CHECK (status IN ('CREATED', 'PAID'))
);

CREATE TABLE sim_payments (
    id             VARCHAR(40) PRIMARY KEY,
    order_id       VARCHAR(40) NOT NULL REFERENCES sim_orders (id),
    amount_paise   BIGINT      NOT NULL,
    status         VARCHAR(10) NOT NULL,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_sim_payments_status CHECK (status IN ('CAPTURED', 'FAILED'))
);
CREATE UNIQUE INDEX uk_sim_payments_one_capture ON sim_payments (order_id) WHERE status = 'CAPTURED';

CREATE TABLE sim_refunds (
    id              VARCHAR(40) PRIMARY KEY,
    payment_id      VARCHAR(40) NOT NULL REFERENCES sim_payments (id),
    idempotency_key VARCHAR(60) NOT NULL,
    amount_paise    BIGINT      NOT NULL,
    status          VARCHAR(10) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    processed_at    TIMESTAMPTZ,
    CONSTRAINT uk_sim_refunds_payment     UNIQUE (payment_id),
    CONSTRAINT uk_sim_refunds_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_sim_refunds_status      CHECK (status IN ('PENDING', 'PROCESSED'))
);
