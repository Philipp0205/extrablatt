ALTER TABLE feeds
    ADD COLUMN category TEXT;

ALTER TABLE articles
    ADD COLUMN read_at TIMESTAMPTZ;

CREATE INDEX idx_feeds_category ON feeds(category);
CREATE INDEX idx_articles_read_at ON articles(read_at);
