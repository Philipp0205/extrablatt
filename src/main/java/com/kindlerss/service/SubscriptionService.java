package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.CancellationRequest;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.CancellationRequestRepository;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Subscription lifecycle: placing an order, applying what the provider reports,
 * accepting a cancellation, and expiring what has quietly lapsed.
 *
 * <p>Two rules run through all of it. The provider callback is the only thing that
 * grants access, so a reader who closes the tab still ends up subscribed and a
 * crafted return URL grants nothing. And nothing is ever deleted on the way down:
 * losing a subscription changes allowances, never feeds or articles.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final CancellationRequestRepository cancellations;
    private final UserRepository users;
    private final BillingMailService mailService;
    private final AppProperties properties;

    public SubscriptionService(SubscriptionRepository subscriptions,
                               CancellationRequestRepository cancellations,
                               UserRepository users,
                               BillingMailService mailService,
                               AppProperties properties) {
        this.subscriptions = subscriptions;
        this.cancellations = cancellations;
        this.users = users;
        this.mailService = mailService;
        this.properties = properties;
    }

    public Subscription forUser(long userId) {
        return subscriptions.findByUserId(userId).orElseGet(() -> Subscription.free(userId));
    }

    /**
     * Records that an order was placed and returns where to send the reader to pay.
     *
     * <p>The consent timestamp is stored because § 356 Abs. 4 BGB only lets the
     * withdrawal right lapse if the consumer agreed to an immediate start and
     * acknowledged what that costs them. Having ticked the boxes is not enough on
     * its own; being able to show they were ticked is the point.
     */
    @Transactional
    public String placeOrder(long userId, BillingInterval interval) {
        AppProperties.Billing billing = properties.billing();
        if (!billing.checkoutConfigured()) {
            throw new IllegalStateException("Payment is not configured on this server yet");
        }
        Subscription existing = forUser(userId);
        subscriptions.save(new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.PENDING,
                interval, billing.provider(), existing.providerCustomerId(),
                existing.providerSubscriptionId(), existing.currentPeriodEnd(),
                false, Instant.now()));
        String url = interval.isMonthly() ? billing.monthlyCheckoutUrl() : billing.yearlyCheckoutUrl();
        // Both Stripe payment links and Paddle hosted checkouts take a parameter that
        // comes back on the callback; it is how a payment finds its way to an account.
        // The name differs per provider, so it is configuration rather than a guess.
        String parameter = URLEncoder.encode(billing.referenceParam(), StandardCharsets.UTF_8);
        return url + (url.contains("?") ? "&" : "?") + parameter + "=" + userId;
    }

    /**
     * Applies what the provider says. Called only from the signed webhook, and
     * written to be safe to call twice with the same content.
     */
    @Transactional
    public void applyProviderUpdate(ProviderSubscriptionUpdate update) {
        Optional<Subscription> found = subscriptions
                .findByProviderSubscriptionId(update.providerSubscriptionId());
        if (found.isEmpty() && update.userId() != null) {
            found = subscriptions.findByUserId(update.userId());
        }
        if (found.isEmpty()) {
            found = subscriptions.findByProviderCustomerId(update.providerCustomerId());
        }
        long userId = found.map(Subscription::userId)
                .orElseGet(() -> Optional.ofNullable(update.userId()).orElseThrow(() ->
                        new IllegalStateException("No account matches subscription "
                                + update.providerSubscriptionId())));
        Subscription current = found.orElseGet(() -> Subscription.free(userId));

        // A grandfathered account that chooses to pay anyway keeps its status: it is
        // a donation, and taking the permanent allowance away for it would be a
        // strange way to say thank you.
        if (current.grandfathered() && update.status() != SubscriptionStatus.ACTIVE) {
            log.info("Ignoring {} for grandfathered account {}", update.type(), userId);
            return;
        }

        SubscriptionStatus status = update.status();
        Subscription merged = new Subscription(
                userId,
                status == SubscriptionStatus.EXPIRED ? Plan.FREE : Plan.SUPPORTER,
                current.grandfathered() ? SubscriptionStatus.GRANDFATHERED : status,
                update.interval() != null ? update.interval() : current.interval(),
                properties.billing().provider(),
                update.providerCustomerId() != null ? update.providerCustomerId() : current.providerCustomerId(),
                update.providerSubscriptionId() != null
                        ? update.providerSubscriptionId() : current.providerSubscriptionId(),
                update.currentPeriodEnd() != null ? update.currentPeriodEnd() : current.currentPeriodEnd(),
                update.cancelAtPeriodEnd(),
                current.withdrawalConsentAt());
        subscriptions.save(merged);
        log.info("Subscription for account {} is now {} until {}", userId, merged.status(),
                merged.currentPeriodEnd());

        if (status == SubscriptionStatus.ACTIVE && current.status() != SubscriptionStatus.ACTIVE) {
            users.findById(userId).ifPresent(user ->
                    mailService.sendSubscriptionConfirmation(user.email(), merged));
        }
    }

    /**
     * Accepts a cancellation declaration under § 312k BGB and returns it, with the
     * moment the contract ends, so the confirmation page and e-mail can state both.
     *
     * <p>The declaration is always recorded and always confirmed, even when nothing
     * matches the address given: the law entitles a consumer to declare a
     * cancellation without signing in, and a page that argues about whether they
     * really have a contract does not satisfy it.
     *
     * <p>What differs is how far it is acted on. An ordinary cancellation only stops
     * the next renewal, so it is applied whoever submitted it — the worst an abused
     * form can do is stop a renewal that the owner can start again, and the owner is
     * told by e-mail either way. An immediate cancellation can involve a refund of a
     * period already paid for, so it is applied straight away only for the signed-in
     * owner; anyone else's is recorded, confirmed and passed to the operator.
     */
    @Transactional
    public Outcome cancel(Declaration declaration, Long signedInUserId) {
        Optional<AppUser> owner = users.findByEmail(declaration.email());
        Long userId = owner.map(AppUser::id).orElse(null);
        boolean bySignedInOwner = signedInUserId != null && signedInUserId.equals(userId);

        Subscription subscription = userId == null ? null : forUser(userId);
        Instant now = Instant.now();
        Instant effectiveAt = now;
        boolean applied = false;

        if (subscription != null && subscription.everOrdered() && !subscription.grandfathered()) {
            boolean immediate = declaration.kind() == CancellationRequest.Kind.IMMEDIATE;
            if (immediate && bySignedInOwner) {
                subscriptions.save(withStatus(subscription, SubscriptionStatus.EXPIRED, now, true));
                applied = true;
            } else if (!immediate) {
                effectiveAt = subscription.currentPeriodEnd() != null
                        ? subscription.currentPeriodEnd() : now;
                subscriptions.save(withStatus(subscription, SubscriptionStatus.CANCELED,
                        subscription.currentPeriodEnd(), true));
                applied = true;
            }
        }

        CancellationRequest recorded = cancellations.insert(userId, declaration.email(),
                declaration.name(), declaration.contractRef(), declaration.kind(),
                declaration.requestedEnd(), declaration.reason(), effectiveAt);

        Outcome outcome = new Outcome(recorded, effectiveAt, applied);
        // A failed e-mail must not undo an accepted cancellation; the declaration is
        // on record either way, and the operator notice is the backstop.
        try {
            mailService.sendCancellationConfirmation(declaration.email(), outcome);
            cancellations.markConfirmed(recorded.id());
        } catch (RuntimeException e) {
            log.warn("Cancellation confirmation e-mail failed for request {}: {}",
                    recorded.id(), e.getMessage());
        }
        try {
            mailService.notifyOperatorOfCancellation(outcome);
        } catch (RuntimeException e) {
            log.warn("Operator cancellation notice failed for request {}: {}",
                    recorded.id(), e.getMessage());
        }
        return outcome;
    }

    /**
     * Drops accounts whose paid period ran out and whose provider never said so.
     * Webhooks get lost, and without this the failure is silent and permanent: an
     * account served for free forever, or a renewal that was paid and never
     * credited. Runs nightly, and the app is a single instance, so no lock is
     * needed.
     */
    @Scheduled(cron = "${app.billing.expiry-cron:0 20 3 * * *}")
    @Transactional
    public int expireLapsed() {
        if (!properties.billing().enabled()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(properties.billing().grace());
        List<Subscription> lapsed = subscriptions.findLapsed(cutoff);
        for (Subscription subscription : lapsed) {
            subscriptions.save(withStatus(subscription, SubscriptionStatus.EXPIRED,
                    subscription.currentPeriodEnd(), subscription.cancelAtPeriodEnd()));
            log.info("Subscription for account {} expired (period ended {})",
                    subscription.userId(), subscription.currentPeriodEnd());
        }
        return lapsed.size();
    }

    private static Subscription withStatus(Subscription subscription, SubscriptionStatus status,
                                           Instant periodEnd, boolean cancelAtPeriodEnd) {
        return new Subscription(subscription.userId(),
                status == SubscriptionStatus.EXPIRED ? Plan.FREE : subscription.plan(),
                status, subscription.interval(), subscription.provider(),
                subscription.providerCustomerId(), subscription.providerSubscriptionId(),
                periodEnd, cancelAtPeriodEnd, subscription.withdrawalConsentAt());
    }

    /** A cancellation exactly as § 312k Abs. 2 BGB lets a consumer state it. */
    public record Declaration(
            String email,
            String name,
            String contractRef,
            CancellationRequest.Kind kind,
            LocalDate requestedEnd,
            String reason
    ) {
    }

    /**
     * What came of a declaration: the record itself, when the contract ends, and
     * whether the subscription was changed on the spot or handed to the operator.
     */
    public record Outcome(CancellationRequest request, Instant effectiveAt, boolean applied) {
    }
}
