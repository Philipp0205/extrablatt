-- Account recovery code. Only a SHA-256 hex hash of the normalized code is stored;
-- the code itself is shown once when it is created and is not kept anywhere. NULL
-- means the account has no code. The unique index makes a hash identify one account,
-- which the later login-by-code step relies on.
ALTER TABLE users ADD COLUMN recovery_hash TEXT;

CREATE UNIQUE INDEX idx_users_recovery_hash ON users(recovery_hash) WHERE recovery_hash IS NOT NULL;
