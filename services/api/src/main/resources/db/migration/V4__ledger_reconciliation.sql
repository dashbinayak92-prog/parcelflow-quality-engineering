ALTER TABLE customer_accounts ADD COLUMN opening_ledger_balance NUMERIC(18,2);
UPDATE customer_accounts a
SET opening_ledger_balance = a.ledger_balance - COALESCE((
    SELECT SUM(CASE
        WHEN t.status <> 'POSTED' THEN 0
        WHEN t.entry_type IN ('TRANSFER_DEBIT', 'PAYMENT_CAPTURE') THEN -t.amount
        WHEN t.entry_type IN ('TRANSFER_CREDIT', 'PAYMENT_REFUND') THEN t.amount
        ELSE 0
    END)
    FROM account_transactions t
    WHERE t.account_id = a.id
), 0);
ALTER TABLE customer_accounts ALTER COLUMN opening_ledger_balance SET NOT NULL;
