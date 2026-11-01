-- The metadata column is part of V1 in the published starter schema.
-- Add the owner-scoped ordering index used by transfer history and support tooling.
CREATE INDEX transfers_owner_created_idx ON transfers(owner_id, created_at DESC);
