-- Which changelog release this account has already been told about, so the
-- "what's new" notice only appears after a newer entry is shipped.
ALTER TABLE users
    ADD COLUMN last_seen_changelog_id VARCHAR(64);
