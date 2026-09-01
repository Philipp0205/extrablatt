package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.repository.RetentionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * The nightly sweep that keeps the app from holding everything for ever.
 *
 * <p>Art. 5(1)(e) GDPR is the reason this exists: personal data may be kept only as
 * long as it is needed for the purpose it was collected for. Before this, four things
 * only grew — delivery history, spent verification tokens, raw payment payloads and
 * cached article text — and "we never got round to deleting it" is not a retention
 * period.
 *
 * <p>Nothing here removes anything a reader would notice. An article's cached text is
 * re-extracted from its own URL the next time it is needed, saved articles are left
 * alone, and the delivery history that goes is history beyond the window the quota and
 * the telemetry page actually read.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final RetentionRepository retention;
    private final AppProperties properties;

    public RetentionService(RetentionRepository retention, AppProperties properties) {
        this.retention = retention;
        this.properties = properties;
    }

    /**
     * Runs shortly after the subscription expiry sweep, on the same single instance,
     * at an hour when a long-running delete costs nobody anything.
     */
    @Scheduled(cron = "${app.retention.cron:0 40 3 * * *}")
    @Transactional
    public Result sweep() {
        AppProperties.Retention policy = properties.retention();
        Instant now = Instant.now();
        Result result = new Result(
                prune(policy.sendEventDays(), now, retention::deleteSendEventsBefore),
                prune(policy.billingPayloadDays(), now, retention::redactBillingPayloadsBefore),
                prune(policy.usedTokenDays(), now, retention::deleteSpentTokensBefore),
                prune(policy.articleCacheDays(), now, retention::clearStaleArticleCacheBefore));
        if (result.touchedAnything()) {
            log.info("Retention sweep: {} send events removed, {} payment payloads redacted, "
                            + "{} spent tokens removed, {} cached articles cleared",
                    result.sendEvents(), result.billingPayloads(), result.spentTokens(),
                    result.articleCaches());
        }
        return result;
    }

    /**
     * Erases what account deletion cannot reach on its own. Payment events carry no
     * cascade — their ids have to outlive the account so a replayed webhook stays a
     * no-op — so the payload is emptied here instead.
     */
    @Transactional
    public void eraseForUser(long userId) {
        int redacted = retention.redactBillingPayloadsForUser(userId);
        if (redacted > 0) {
            log.info("Redacted {} payment payloads for a deleted account", redacted);
        }
    }

    /** Zero days switches a sweep off; an operator may have a reason to keep something. */
    private static int prune(int days, Instant now, java.util.function.ToIntFunction<Instant> sweep) {
        if (days <= 0) {
            return 0;
        }
        return sweep.applyAsInt(now.minus(Duration.ofDays(days)));
    }

    public record Result(int sendEvents, int billingPayloads, int spentTokens, int articleCaches) {

        boolean touchedAnything() {
            return sendEvents + billingPayloads + spentTokens + articleCaches > 0;
        }
    }
}
