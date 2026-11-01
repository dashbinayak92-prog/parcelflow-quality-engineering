ALTER TABLE transfers
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
