-- Removes personal data from a production copy before staging may use it.
--
-- deploy/sync-staging-db.sh streams the production dump, the keep list and this
-- file into one psql transaction on staging, so either the scrubbed copy is
-- committed or staging keeps what it had. Unscrubbed production data is never
-- committed on staging.
--
-- Accounts whose e-mail is in pg_temp.staging_keep_emails (STAGING_KEEP_EMAILS,
-- e.g. the testers and ADMIN_EMAILS) stay as they are so they can still sign in.
-- Every other account gets an address under the reserved .invalid domain and a
-- password hash that matches nothing.
--
-- Plain SQL on purpose (no psql meta-commands), so the test suite can run it
-- over JDBC. StagingScrubTest fails when a migration adds a text column that is
-- not classified here, so new personal data cannot slip through unnoticed.

-- pg_dump output clears search_path for its own session.
SET search_path TO public;

CREATE TEMP TABLE IF NOT EXISTS staging_keep_emails (email TEXT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE staging_kept_users ON COMMIT DROP AS
SELECT u.id FROM users u
WHERE lower(u.email) IN (SELECT lower(email) FROM pg_temp.staging_keep_emails);

-- Accounts: addresses, credentials and the newsletter inbox address.
UPDATE users
SET email = 'user-' || id || '@staging.invalid',
    password_hash = '!scrubbed-for-staging',
    kindle_email = CASE WHEN kindle_email IS NULL THEN NULL
                        ELSE 'kindle-' || id || '@staging.invalid' END,
    newsletter_inbound_token = NULL
WHERE id NOT IN (SELECT id FROM pg_temp.staging_kept_users);

-- Verification and reset links are bearer credentials.
DELETE FROM email_tokens;

-- Newsletter feeds are named after, and keyed by, the sender's address.
UPDATE feeds
SET url = 'newsletter:sender-' || id || '@staging.invalid',
    title = 'Newsletter ' || id,
    site_url = NULL
WHERE source = 'NEWSLETTER';

-- Feed URLs can carry credentials (user:pass@ or private ?token= links).
UPDATE feeds
SET url = regexp_replace(
        CASE WHEN position('?' IN url) > 0
             THEN split_part(url, '?', 1) || '?staging-redacted=' || id
             ELSE url END,
        '^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@', '\1')
WHERE source = 'RSS'
  AND (position('?' IN url) > 0 OR url ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^/@]*@');

UPDATE feeds SET last_error = NULL WHERE last_error IS NOT NULL;

-- Newsletter issues came out of someone's inbox.
UPDATE articles a
SET guid = 'staging-' || a.id,
    title = 'Newsletter issue ' || a.id,
    url = NULL,
    author = NULL,
    summary_html = CASE WHEN a.summary_html IS NULL THEN NULL
                        ELSE '<p>Scrubbed for staging.</p>' END,
    feed_content_html = CASE WHEN a.feed_content_html IS NULL THEN NULL
                             ELSE '<p>Scrubbed for staging.</p>' END,
    extracted_content_html = CASE WHEN a.extracted_content_html IS NULL THEN NULL
                                  ELSE '<p>Scrubbed for staging.</p>' END
FROM feeds f
WHERE f.id = a.feed_id AND f.source = 'NEWSLETTER';

-- Payment provider references and raw webhook payloads.
UPDATE subscriptions
SET provider_customer_id = CASE WHEN provider_customer_id IS NULL THEN NULL
                                ELSE 'staging-cus-' || user_id END,
    provider_subscription_id = CASE WHEN provider_subscription_id IS NULL THEN NULL
                                    ELSE 'staging-sub-' || user_id END;

UPDATE billing_events
SET payload = '{}',
    error = CASE WHEN error IS NULL THEN NULL ELSE 'scrubbed for staging' END;

-- Cancellation declarations can be filed without an account.
UPDATE cancellation_requests
SET email = 'cancel-' || id || '@staging.invalid',
    name = CASE WHEN name IS NULL THEN NULL ELSE 'Staging ' || id END,
    contract_ref = NULL,
    reason = CASE WHEN reason IS NULL THEN NULL ELSE 'scrubbed for staging' END
WHERE user_id IS NULL OR user_id NOT IN (SELECT id FROM pg_temp.staging_kept_users);

-- Sessions and remember-me tokens are not stored today; clear them if a later
-- change adds the usual Spring tables.
DO $$
BEGIN
    IF to_regclass('public.persistent_logins') IS NOT NULL THEN
        EXECUTE 'TRUNCATE public.persistent_logins';
    END IF;
    IF to_regclass('public.spring_session') IS NOT NULL THEN
        EXECUTE 'TRUNCATE public.spring_session CASCADE';
    END IF;
END $$;

-- Refuse to commit if anything reachable by e-mail is left over.
DO $$
DECLARE
    leftover BIGINT;
BEGIN
    SELECT count(*) INTO leftover FROM users
    WHERE id NOT IN (SELECT id FROM pg_temp.staging_kept_users)
      AND (email NOT LIKE '%@staging.invalid'
           OR (kindle_email IS NOT NULL AND kindle_email NOT LIKE '%@staging.invalid'));
    IF leftover > 0 THEN
        RAISE EXCEPTION 'staging scrub left % account(s) with a real address', leftover;
    END IF;
    SELECT count(*) INTO leftover FROM cancellation_requests
    WHERE (user_id IS NULL OR user_id NOT IN (SELECT id FROM pg_temp.staging_kept_users))
      AND email NOT LIKE '%@staging.invalid';
    IF leftover > 0 THEN
        RAISE EXCEPTION 'staging scrub left % cancellation request(s) with a real address', leftover;
    END IF;
END $$;
