-- Opt-in: articles in the list are marked read once the reader has scrolled
-- past them. Off by default, so nothing changes for anyone until they ask.
ALTER TABLE users
    ADD COLUMN mark_read_on_scroll BOOLEAN NOT NULL DEFAULT FALSE;
