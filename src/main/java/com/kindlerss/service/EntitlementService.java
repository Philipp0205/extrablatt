package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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

    /** The month boundary readers are billed and reset against. */
    private static final ZoneId MONTH_ZONE = ZoneId.of("Europe/Berlin");

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
        Subscription subscription = subscription(userId);
        Plan plan = planFor(subscription);
        AppProperties.Billing billing = properties.billing();
        boolean paid = plan == Plan.SUPPORTER;
        // After the trial there is no ongoing free ration unless an operator still
        // configured one. Paid access has no monthly meter; the daily guardrail stays.
        int planMonthlySends = paid ? 0 : billing.freeMaxSendsPerMonth();
        int planDailySends = paid
                ? properties.limits().maxSendsPerDay()
                : billing.freeMaxSendsPerMonth();
        int planFeeds = paid
                ? properties.limits().maxFeedsPerUser()
                : billing.freeMaxFeeds();
        // An administrator's override is about one account's daily behaviour — usually
        // reining in an abusive one. It deliberately does not lift a monthly ration;
        // "Grant Supporter" on the same page is how somebody gets the paid plan.
        Integer override = sendLimits.findByUserId(userId)
                .map(com.kindlerss.domain.UserSendLimit::maxSendsPerDay)
                .orElse(null);
        Instant trialEndsAt = subscription.trialing() && hasPaidAccess(subscription, Instant.now())
                ? subscription.currentPeriodEnd()
                : null;
        return new Entitlement(plan,
                override == null ? planDailySends : override,
                planMonthlySends,
                planFeeds,
                paid,
                trialEndsAt);
    }

    /**
     * When the free plan's allowance last reset. A calendar month, in Central European
     * Time, because "ten a month" should mean what a reader assumes it means — it
     * refills on the first — rather than a rolling window that refuses a send for
     * reasons only the database can explain.
     */
    public Instant startOfCurrentMonth() {
        return LocalDate.now(MONTH_ZONE).withDayOfMonth(1).atStartOfDay(MONTH_ZONE).toInstant();
    }

    /** When it next resets, so a page can say so instead of leaving the reader guessing. */
    public LocalDate nextResetDate() {
        return LocalDate.now(MONTH_ZONE).withDayOfMonth(1).plusMonths(1);
    }

    /**
     * Which plan applies right now. With billing switched off every account is on
     * the paid plan, which is what keeps a self-hosted deployment — and every
     * existing test — behaving exactly as it did before subscriptions existed.
     */
    public Plan planFor(long userId) {
        return planFor(subscription(userId));
    }

    private Plan planFor(Subscription subscription) {
        if (!properties.billing().enabled()) {
            return Plan.SUPPORTER;
        }
        return hasPaidAccess(subscription, Instant.now()) ? Plan.SUPPORTER : Plan.FREE;
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
            case TRIALING -> subscription.withinPaidPeriod(now);
            // A trial still running is kept on the order row as currentPeriodEnd, so
            // walking over to pay does not end the week early.
            case PENDING -> subscription.withinPaidPeriod(now);
            case ACTIVE, PAST_DUE -> withinGrace(subscription, now);
            case FREE, EXPIRED -> false;
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
