CREATE TABLE customer_payment_transactions (
    id                         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider                   VARCHAR(24)  NOT NULL,
    payment_id                 VARCHAR(120) NOT NULL,
    provider_order_id          VARCHAR(120),
    order_number               VARCHAR(120),
    latest_event_type          VARCHAR(120) NOT NULL,
    latest_provider_event_id   VARCHAR(160),
    payment_method             VARCHAR(40),
    payment_status             VARCHAR(40)  NOT NULL,
    amount_minor               BIGINT,
    currency                   VARCHAR(16),
    is_captured                BOOLEAN,
    captured_at                TIMESTAMPTZ,
    last_webhook_received_at   TIMESTAMPTZ  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_payment_tx_provider
        CHECK (provider IN ('RAZORPAY')),
    CONSTRAINT chk_customer_payment_tx_status
        CHECK (payment_status IN ('AUTHORIZED','CAPTURED','FAILED','REFUNDED','UNKNOWN'))
);

CREATE UNIQUE INDEX uq_customer_payment_tx_provider_payment
    ON customer_payment_transactions (provider, payment_id);

CREATE INDEX idx_customer_payment_tx_order
    ON customer_payment_transactions (order_number, last_webhook_received_at DESC);

CREATE INDEX idx_customer_payment_tx_status
    ON customer_payment_transactions (payment_status, last_webhook_received_at DESC);
