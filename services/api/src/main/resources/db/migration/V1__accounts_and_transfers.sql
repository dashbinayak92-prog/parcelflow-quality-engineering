CREATE TABLE customer_accounts (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    masked_number VARCHAR(12) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    ledger_balance NUMERIC(18,2) NOT NULL CHECK (ledger_balance >= 0),
    pending_debit NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (pending_debit >= 0),
    currency CHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT customer_accounts_masked_number_format CHECK (masked_number ~ '^\*{4}[0-9]{4}$')
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    source_account_id UUID NOT NULL REFERENCES customer_accounts(id),
    destination_account_id UUID NOT NULL REFERENCES customer_accounts(id),
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'DECLINED')),
    memo VARCHAR(140),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE account_transactions (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES customer_accounts(id),
    entry_type VARCHAR(30) NOT NULL CHECK (entry_type IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'REVERSED')),
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    description VARCHAR(140),
    created_at TIMESTAMPTZ NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_transactions_account_created_idx ON account_transactions(account_id, created_at DESC);

CREATE TABLE transfer_idempotency (
    owner_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    transfer_id UUID UNIQUE REFERENCES transfers(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);

INSERT INTO customer_accounts (id, owner_id, display_name, masked_number, account_type, status, ledger_balance, pending_debit, currency, version) VALUES
('10000000-0000-4000-8000-000000000001', 'demo-customer-1', 'Everyday Checking', '****4821', 'CHECKING', 'ACTIVE', 2840.50, 225.00, 'USD', 7),
('10000000-0000-4000-8000-000000000002', 'demo-customer-1', 'Rainy Day Savings', '****9074', 'SAVINGS', 'ACTIVE', 12500.00, 0.00, 'USD', 3),
('10000000-0000-4000-8000-000000000003', 'demo-customer-1', 'Euro Travel Account', '****3312', 'CHECKING', 'ACTIVE', 750.00, 0.00, 'EUR', 1);

