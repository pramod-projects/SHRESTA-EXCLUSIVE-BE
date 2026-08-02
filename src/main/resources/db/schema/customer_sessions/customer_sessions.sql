CREATE TABLE customer_sessions (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id        UUID        NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
    session_token_hash VARCHAR(128) NOT NULL UNIQUE,
    status             VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_session_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))
);

CREATE INDEX idx_customer_sessions_customer      ON customer_sessions (customer_id, status, expires_at DESC);
CREATE INDEX idx_customer_sessions_active_expiry ON customer_sessions (status, expires_at);
