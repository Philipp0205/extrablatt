-- Last successful login, written on form login and remember-me authentication.
-- Null means the account has not logged in since this column was added.
ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ;
