-- Detect attempts to reuse an idempotency key with a different booking payload.
ALTER TABLE idempotency_keys ADD COLUMN request_hash varchar(64);
