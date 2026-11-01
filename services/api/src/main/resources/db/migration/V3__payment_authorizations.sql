ALTER TABLE account_transactions ALTER COLUMN transfer_id DROP NOT NULL;
ALTER TABLE account_transactions ALTER COLUMN posted_at DROP NOT NULL;
ALTER TABLE account_transactions ADD COLUMN payment_authorization_id UUID;
ALTER TABLE account_transactions ADD COLUMN payment_operation_id UUID;
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_entry_type_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_entry_type_check
    CHECK (entry_type IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT', 'PAYMENT_AUTHORIZATION', 'PAYMENT_CAPTURE', 'PAYMENT_REFUND'));
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_status_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_status_check
    CHECK (status IN ('PENDING', 'POSTED', 'REVERSED', 'DECLINED'));

CREATE TABLE payment_authorizations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    account_id UUID NOT NULL REFERENCES customer_accounts(id),
    token_fingerprint CHAR(64) NOT NULL,
    merchant_reference VARCHAR(64) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'VOIDED', 'PARTIALLY_CAPTURED', 'CAPTURED', 'EXPIRED')),
    decline_code VARCHAR(40),
    capture_mode VARCHAR(12) NOT NULL CHECK (capture_mode IN ('MANUAL', 'AUTOMATIC')),
    captured_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
    refunded_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_authorizations_owner_created_idx ON payment_authorizations(owner_id, created_at DESC);

CREATE TABLE payment_operations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    authorization_id UUID NOT NULL REFERENCES payment_authorizations(id),
    type VARCHAR(12) NOT NULL CHECK (type IN ('CAPTURE', 'VOID', 'REFUND')),
    status VARCHAR(12) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_operations_authorization_idx ON payment_operations(authorization_id, created_at);

CREATE TABLE payment_idempotency (
    owner_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(20),
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);

ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_payment_authorization_fk FOREIGN KEY (payment_authorization_id) REFERENCES payment_authorizations(id),
    ADD CONSTRAINT account_transactions_payment_operation_fk FOREIGN KEY (payment_operation_id) REFERENCES payment_operations(id);
