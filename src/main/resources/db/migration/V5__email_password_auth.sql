-- Preserve legacy users; password enrollment requires separate identity verification.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users ADD COLUMN password_hash varchar(100);
ALTER TABLE users ADD COLUMN failed_login_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until timestamptz;
-- Resolve existing duplicate emails explicitly before applying this migration.
CREATE UNIQUE INDEX uq_users_email_normalized ON users (lower(btrim(email))) WHERE email IS NOT NULL;
