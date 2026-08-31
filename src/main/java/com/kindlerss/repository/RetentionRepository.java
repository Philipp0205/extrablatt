package com.kindlerss.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The delete statements behind the retention sweep, kept together rather than
 * scattered across the repositories they touch. What is being expressed here is one
 * policy — "hold nothing longer than it is needed for" — and splitting it across
 * five classes would make it impossible to read that policy off the code.
 */
@Repository
public class RetentionRepository {

    private final JdbcTemplate jdbc;

    public RetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Drops delivery history past the retention window. The rolling quota needs one
     * day of it and the telemetry page needs seven; the rest was only ever a lifetime
     * counter.
     */
    public int deleteSendEventsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM article_send_events WHERE sent_at < ?", Timestamp.from(cutoff));
    }

    /**
     * Empties the stored provider payload while keeping the event id. The id is what
     * makes a replayed webhook a no-op, so it has to stay; the JSON body, which can
     * carry a customer's name and e-mail, does not.
     */
    public int redactBillingPayloadsBefore(Instant cutoff) {
        return jdbc.update("""
                UPDATE billing_events
                SET payload = ''
                WHERE received_at < ? AND payload <> ''
                """, Timestamp.from(cutoff));
    }

    /** Same redaction for one account, used when that account is erased. */
    public int redactBillingPayloadsForUser(long userId) {
        return jdbc.update("UPDATE billing_events SET payload = '' WHERE user_id = ?", userId);
    }

    /**
     * Removes verification and reset tokens that are spent or long expired. A used
     * token is a record that somebody asked to reset a password, which is not worth
     * keeping once it can no longer be redeemed.
     */
    public int deleteSpentTokensBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM email_tokens
                WHERE (used_at IS NOT NULL AND used_at < ?)
                   OR expires_at < ?
                """, Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    /**
     * Clears cached extracted article text that has served its purpose: old, already
     * read, and not saved for later. Nothing is lost — {@code ArticleService}
     * re-extracts from the article's URL when the text is next needed — so this trades
     * a rare refetch for not holding a reader's whole reading history in full text.
     */
    public int clearStaleArticleCacheBefore(Instant cutoff) {
        return jdbc.update("""
                UPDATE articles
                SET extracted_content_html = NULL, updated_at = NOW()
                WHERE extracted_content_html IS NOT NULL
                  AND created_at < ?
                  AND read = TRUE
                  AND saved_at IS NULL
                """, Timestamp.from(cutoff));
    }
}
