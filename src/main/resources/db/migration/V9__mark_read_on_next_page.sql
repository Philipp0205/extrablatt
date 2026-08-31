-- How the article list treats unread items: paging past a screenful marks them
-- read (the Kindle-first default), or new feed entries arrive already read so
-- a refresh does not dump a backlog into Unread.
ALTER TABLE users
    ADD COLUMN mark_read_on_next_page BOOLEAN NOT NULL DEFAULT TRUE;
