-- Per-user send controls managed from the admin telemetry page. A missing row
-- inherits the application-wide MAX_SENDS_PER_DAY setting.
CREATE TABLE user_send_limits (
    user_id             BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    max_sends_per_day   INTEGER,
    blocked_until       TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_user_send_limit_positive
        CHECK (max_sends_per_day IS NULL OR max_sends_per_day > 0)
);

-- One row per successful delivery attempt. articles.sent_at remains the latest
-- delivery timestamp for the reader UI; this event table makes telemetry and
-- rolling quotas accurate when the same article is sent more than once.
CREATE TABLE article_send_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    article_id  BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_send_events_user_time
    ON article_send_events(user_id, sent_at DESC);

-- Preserve the latest known send from installations upgraded from V3. Earlier
-- repeat sends were not historically recorded and therefore cannot be recovered.
INSERT INTO article_send_events (user_id, article_id, sent_at)
SELECT f.user_id, a.id, a.sent_at
FROM articles a
JOIN feeds f ON f.id = a.feed_id
WHERE a.sent_at IS NOT NULL;
