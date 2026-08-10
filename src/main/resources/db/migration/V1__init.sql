-- Feeds and articles for Kindle RSS

CREATE TABLE feeds (
    id              BIGSERIAL PRIMARY KEY,
    title           TEXT NOT NULL,
    url             TEXT NOT NULL UNIQUE,
    site_url        TEXT,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE articles (
    id                      BIGSERIAL PRIMARY KEY,
    feed_id                 BIGINT NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
    guid                    TEXT NOT NULL,
    title                   TEXT NOT NULL,
    url                     TEXT,
    author                  TEXT,
    published_at            TIMESTAMPTZ,
    summary_html            TEXT,
    feed_content_html       TEXT,
    extracted_content_html  TEXT,
    read                    BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_articles_feed_guid UNIQUE (feed_id, guid)
);

CREATE INDEX idx_articles_feed_id ON articles(feed_id);
CREATE INDEX idx_articles_read ON articles(read);
CREATE INDEX idx_articles_published_at ON articles(published_at DESC NULLS LAST);
