CREATE TABLE customer_otp_challenges (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_type  VARCHAR(24) NOT NULL,
    identity_value VARCHAR(254) NOT NULL,
    purpose        VARCHAR(32) NOT NULL,
    otp_hash       VARCHAR(128) NOT NULL,
    status         VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER     NOT NULL DEFAULT 0,
    max_attempts   INTEGER     NOT NULL DEFAULT 5,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_otp_identity_type CHECK (identity_type IN ('EMAIL','MOBILE')),
    CONSTRAINT chk_customer_otp_purpose       CHECK (purpose IN ('LOGIN','PROFILE_UPDATE')),
    CONSTRAINT chk_customer_otp_status        CHECK (status IN ('PENDING','VERIFIED','EXPIRED','LOCKED')),
    CONSTRAINT chk_customer_otp_attempts      CHECK (attempts >= 0 AND attempts <= max_attempts),
    CONSTRAINT chk_customer_otp_max_attempts  CHECK (max_attempts BETWEEN 1 AND 10)
);

CREATE INDEX idx_customer_otp_lookup ON customer_otp_challenges (identity_value, purpose, status, expires_at DESC);
