-- Complimentary first week instead of an ongoing free ration. Existing accounts
-- that never ordered (no subscriptions row) get a week from this deploy; anyone
-- already grandfathered, paid, pending or expired is left alone.
ALTER TABLE subscriptions DROP CONSTRAINT chk_subscription_status;
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscription_status
    CHECK (status IN ('FREE', 'TRIALING', 'PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'GRANDFATHERED'));

INSERT INTO subscriptions (user_id, plan, status, current_period_end, cancel_at_period_end)
SELECT id, 'SUPPORTER', 'TRIALING', NOW() + INTERVAL '7 days', TRUE
FROM users
WHERE NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = users.id);
