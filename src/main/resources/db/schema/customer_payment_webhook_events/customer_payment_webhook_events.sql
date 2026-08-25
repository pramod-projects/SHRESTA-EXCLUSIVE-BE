CREATE TABLE customer_payment_webhook_events (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider          VARCHAR(24)  NOT NULL,
    provider_event_id VARCHAR(160),
    event_type        VARCHAR(120) NOT NULL,
    payment_id        VARCHAR(120),
    order_number      VARCHAR(120),
    signature         VARCHAR(255),
    payload_json      JSONB        NOT NULL,
    status            VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
    failure_reason    VARCHAR(500),
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_payment_webhook_provider
        CHECK (provider IN ('RAZORPAY')),
    CONSTRAINT chk_customer_payment_webhook_status
        CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED','REJECTED'))
);

CREATE UNIQUE INDEX uq_customer_payment_webhook_provider_event
    ON customer_payment_webhook_events (provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE INDEX idx_customer_payment_webhook_order
    ON customer_payment_webhook_events (order_number, created_at DESC);

CREATE INDEX idx_customer_payment_webhook_status
    ON customer_payment_webhook_events (status, created_at DESC);
