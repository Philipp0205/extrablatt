package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The single answer to "what is this account allowed to do". Limits used to be
 * read straight from configuration at each call site; they now come from here, so
 * that a plan, an administrator's override and the billing feature flag cannot
 * disagree with each other.
 *
 * <p>Precedence, strongest first: an administrator's custom daily limit, then the
 * account's plan, then the configured defaults. Administrative blocking is
 * deliberately left where it was, in {@link KindleMailService}, because a block is
 * not an allowance.
 */
@Service
public class EntitlementService {

    private final SubscriptionRepository subscriptions;
    private final UserSendLimitRepository sendLimits;
    private final AppProperties properties;

    public EntitlementService(SubscriptionRepository subscriptions,
                              UserSendLimitRepository sendLimits,
                              AppProperties properties) {
        this.subscriptions = subscriptions;
        this.sendLimits = sendLimits;
        this.properties = properties;
    }

    public Entitlement forUser(long userId) {
        Plan plan = planFor(userId);
        AppProperties.Billing billing = properties.billing();
        int planSends = plan == Plan.SUPPORTER
                ? properties.limits().maxSendsPerDay()
                : billing.freeMaxSendsPerDay();
        int planFeeds = plan == Plan.SUPPORTER
                ? properties.limits().maxFeedsPerUser()
                : billing.freeMaxFeeds();
        Integer override = sendLimits.findByUserId(userId)
                .map(com.kindlerss.domain.UserSendLimit::maxSendsPerDay)
                .orElse(null);
        return new Entitlement(plan,
                override == null ? planSends : override,
                planFeeds,
                plan == Plan.SUPPORTER);
    }

    /**
     * Which plan applies right now. With billing switched off every account is on
     * the paid plan, which is what keeps a self-hosted deployment — and every
     * existing test — behaving exactly as it did before subscriptions existed.
     */
    public Plan planFor(long userId) {
        if (!properties.billing().enabled()) {
            return Plan.SUPPORTER;
        }
        return hasPaidAccess(subscription(userId), Instant.now()) ? Plan.SUPPORTER : Plan.FREE;
    }

    /** The account's subscription, or a free one when it has never ordered. */
    public Subscription subscription(long userId) {
        return subscriptions.findByUserId(userId).orElseGet(() -> Subscription.free(userId));
    }

    /**
     * Whether a subscription currently opens the paid plan.
     *
     * <p>A cancelled subscription keeps its access to the end of the period that was
     * paid for. A failed renewal keeps it for the grace period on top, because a
     * card that expires overnight should not silently cut a reader off; the same
     * grace covers an active subscription whose renewal confirmation never arrived,
     * which is the failure mode of a lost webhook.
     */
    public boolean hasPaidAccess(Subscription subscription, Instant now) {
        return switch (subscription.status()) {
            case GRANDFATHERED -> true;
            case CANCELED -> subscription.withinPaidPeriod(now);
            case ACTIVE, PAST_DUE -> withinGrace(subscription, now);
            case FREE, PENDING, EXPIRED -> false;
        };
    }

    private boolean withinGrace(Subscription subscription, Instant now) {
        Instant end = subscription.currentPeriodEnd();
        if (end == null) {
            // Activated without a period end (a provider that does not report one);
            // trust the status until the provider says otherwise.
            return subscription.status() == com.kindlerss.domain.SubscriptionStatus.ACTIVE;
        }
        return end.plus(properties.billing().grace()).isAfter(now);
    }
}
