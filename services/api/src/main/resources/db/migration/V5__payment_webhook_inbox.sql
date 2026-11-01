CREATE TABLE payment_webhook_inbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(60) NOT NULL,
    authorization_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED')),
    delivery_attempts INTEGER NOT NULL DEFAULT 1 CHECK (delivery_attempts > 0),
    last_error VARCHAR(60),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ
);
CREATE INDEX payment_webhook_status_received_idx ON payment_webhook_inbox(status, received_at);
