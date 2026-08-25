CREATE TABLE email_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    provider_message_id VARCHAR(255),
    event_type VARCHAR(32) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processing_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_event_id)
);