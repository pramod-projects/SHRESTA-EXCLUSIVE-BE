CREATE TABLE customer_chat_messages (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID        NOT NULL REFERENCES customer_chat_sessions(id) ON DELETE CASCADE,
    sender       VARCHAR(24) NOT NULL,
    message_type VARCHAR(24) NOT NULL DEFAULT 'TEXT',
    message_text TEXT        NOT NULL,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_chat_sender            CHECK (sender IN ('CUSTOMER','ASSISTANT','SUPPORT_AGENT','SYSTEM')),
    CONSTRAINT chk_customer_chat_message_type       CHECK (message_type IN ('TEXT','QUICK_ACTION','SYSTEM_EVENT')),
    CONSTRAINT chk_customer_chat_message_not_blank  CHECK (length(trim(message_text)) > 0)
);

CREATE INDEX idx_customer_chat_messages_session ON customer_chat_messages (session_id, created_at);
