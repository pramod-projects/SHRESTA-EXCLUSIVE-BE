CREATE TABLE email_delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_id UUID NOT NULL REFERENCES email_outbox(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_idempotency_key VARCHAR(200) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    http_status INTEGER,
    provider_message_id VARCHAR(255),
    failure_class VARCHAR(48),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outbox_id, attempt_number, provider)
);

CREATE INDEX idx_email_attempts_outbox ON email_delivery_attempts (outbox_id, attempted_at);