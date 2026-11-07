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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
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

    /** How long a cancellation confirmation link works. */
    static final Duration LINK_VALIDITY = Duration.ofHours(24);

    /**
     * At most this many confirmation links per account per {@link #LINK_WINDOW}, so
     * the public form cannot be used to flood someone's inbox. Declarations past the
     * limit are still recorded and reach the operator.
     */
    static final int MAX_LINKS_PER_WINDOW = 3;
    static final Duration LINK_WINDOW = Duration.ofDays(1);

    private final SubscriptionRepository subscriptions;
    private final CancellationRequestRepository cancellations;
    private final UserRepository users;
    private final BillingMailService mailService;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

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
        return subscriptions.findByUserId(userId)
                .orElseGet(() -> Subscription.notOnFile(userId, properties.billing().enabled()));
    }

    /**
     * Starts the complimentary week for a newly registered account. Idempotent:
     * an account that already has a real standing (including an ended trial) is
     * left alone, so registering twice cannot mint a second week.
     *
     * <p>A deployment that charges nothing writes no row at all, rather than a week
     * that would already have run out by the time anyone switched charging on. What
     * those accounts are owed is decided later, by
     * {@link Subscription#notOnFile(long, boolean)}.
     */
    @Transactional
    public void startTrial(long userId) {
        AppProperties.Billing billing = properties.billing();
        if (!billing.enabled() || billing.trialDays() <= 0) {
            return;
        }
        Subscription existing = subscriptions.findByUserId(userId).orElse(null);
        if (existing != null && existing.status() != SubscriptionStatus.FREE) {
            return;
        }
        Instant ends = Instant.now().plus(Duration.ofDays(billing.trialDays()));
        subscriptions.save(new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.TRIALING,
                null, null, null, null, ends, true, null));
        log.info("Started a {}-day trial for account {} until {}", billing.trialDays(), userId, ends);
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
        // Writing the PENDING order row would replace GRANDFATHERED, and a pending
        // order with no paid period grants nothing: the permanent free plan would be
        // gone the moment the reader clicked, whether or not they ever paid.
        if (existing.grandfathered()) {
            throw new AlreadySupporterException();
        }
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
     * moment the contract ends when that is already known.
     *
     * <p>The declaration is always recorded, with the time it arrived, even when
     * nothing matches the address given: the law entitles a consumer to declare a
     * cancellation without signing in, and a page that argues about whether they
     * really have a contract does not satisfy it.
     *
     * <p>What differs is who can make it take effect. The signed-in owner of the
     * account the address belongs to gets it applied on the spot, ordinary or
     * immediate, with the full confirmation. Anyone else has only typed an address,
     * which proves nothing, so the subscription is left alone and a single-use link
     * goes to the account's own address instead (see {@link #confirmByLink}). That
     * e-mail carries none of the text the requester typed, so the form cannot be
     * used to send arbitrary messages from this server to arbitrary people. The
     * answer on screen is the same whether or not an account was found, so the form
     * does not tell anyone who has one.
     */
    @Transactional
    public Outcome cancel(Declaration declaration, Long signedInUserId) {
        Optional<AppUser> owner = users.findByEmail(declaration.email());
        Long userId = owner.map(AppUser::id).orElse(null);
        boolean bySignedInOwner = signedInUserId != null && signedInUserId.equals(userId);

        if (!bySignedInOwner) {
            return recordForOwnerToConfirm(declaration, owner.orElse(null));
        }

        Instant now = Instant.now();
        Effect effect = apply(forUser(userId), declaration.kind(), now);
        CancellationRequest recorded = cancellations.insert(userId, declaration.email(),
                declaration.name(), declaration.contractRef(), declaration.kind(),
                declaration.requestedEnd(), declaration.reason(), effect.effectiveAt());
        if (effect.applied()) {
            cancellations.recordEffect(recorded.id(), effect.effectiveAt(), true);
        }
        Outcome outcome = new Outcome(recorded, effect.effectiveAt(), effect.applied(), Route.OWNER);
        confirmAndNotify(declaration.email(), outcome);
        return outcome;
    }

    /**
     * The declaration behind a confirmation link, if the link can still be used.
     * Looking does not spend it: mail scanners open links, and the owner should still
     * be able to confirm after theirs has.
     */
    public Optional<CancellationRequest> peekLink(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return cancellations.findUsableToken(hashToken(token))
                .map(pending -> cancellations.findById(pending.requestId()));
    }

    /**
     * Applies a declaration once the account's owner has followed the link sent to
     * their address. That proves the same thing being signed in does, so ordinary
     * and immediate cancellations both take effect, exactly as for the signed-in
     * owner. Empty when the link is unknown, used or expired.
     */
    @Transactional
    public Optional<Outcome> confirmByLink(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<CancellationRequestRepository.PendingToken> spent =
                cancellations.consumeToken(hashToken(token));
        if (spent.isEmpty()) {
            return Optional.empty();
        }
        long userId = spent.get().userId();
        CancellationRequest request = cancellations.findById(spent.get().requestId());
        Effect effect = apply(forUser(userId), request.kind(), Instant.now());
        cancellations.recordEffect(request.id(), effect.effectiveAt(), effect.applied());
        Outcome outcome = new Outcome(request, effect.effectiveAt(), effect.applied(),
                Route.CONFIRMED_BY_LINK);
        String accountEmail = users.findById(userId).map(AppUser::email).orElse(request.email());
        confirmAndNotify(accountEmail, outcome);
        return Optional.of(outcome);
    }

    private Outcome recordForOwnerToConfirm(Declaration declaration, AppUser owner) {
        CancellationRequest recorded = cancellations.insert(owner == null ? null : owner.id(),
                declaration.email(), declaration.name(), declaration.contractRef(),
                declaration.kind(), declaration.requestedEnd(), declaration.reason(), null);
        Route route = Route.RECORDED;
        if (owner != null && hasSomethingToCancel(forUser(owner.id()))) {
            Instant now = Instant.now();
            if (cancellations.countTokensSince(owner.id(), now.minus(LINK_WINDOW)) < MAX_LINKS_PER_WINDOW) {
                String token = newToken();
                cancellations.insertToken(hashToken(token), recorded.id(), owner.id(),
                        now.plus(LINK_VALIDITY));
                try {
                    mailService.sendCancellationLink(owner.email(), recorded, token);
                    route = Route.LINK_SENT;
                } catch (RuntimeException e) {
                    log.warn("Cancellation link e-mail failed for request {}: {}",
                            recorded.id(), e.getMessage());
                }
            } else {
                log.info("Cancellation request {} recorded without a link: account {} was sent "
                        + "{} already in the last {}", recorded.id(), owner.id(),
                        MAX_LINKS_PER_WINDOW, LINK_WINDOW);
            }
        }
        Outcome outcome = new Outcome(recorded, null, false, route);
        notifyOperator(outcome);
        return outcome;
    }

    /** Changes the subscription as the owner asked. */
    private Effect apply(Subscription subscription, CancellationRequest.Kind kind, Instant now) {
        if (!hasSomethingToCancel(subscription)) {
            return new Effect(now, false);
        }
        if (kind == CancellationRequest.Kind.IMMEDIATE) {
            subscriptions.save(withStatus(subscription, SubscriptionStatus.EXPIRED, now, true));
            return new Effect(now, true);
        }
        Instant effectiveAt = subscription.currentPeriodEnd() != null
                ? subscription.currentPeriodEnd() : now;
        subscriptions.save(withStatus(subscription, SubscriptionStatus.CANCELED,
                subscription.currentPeriodEnd(), true));
        return new Effect(effectiveAt, true);
    }

    private static boolean hasSomethingToCancel(Subscription subscription) {
        return subscription != null && subscription.everOrdered() && !subscription.grandfathered();
    }

    private void confirmAndNotify(String toEmail, Outcome outcome) {
        // A failed e-mail must not undo an accepted cancellation; the declaration is
        // on record either way, and the operator notice is the backstop.
        try {
            mailService.sendCancellationConfirmation(toEmail, outcome);
            cancellations.markConfirmed(outcome.request().id());
        } catch (RuntimeException e) {
            log.warn("Cancellation confirmation e-mail failed for request {}: {}",
                    outcome.request().id(), e.getMessage());
        }
        notifyOperator(outcome);
    }

    private void notifyOperator(Outcome outcome) {
        try {
            mailService.notifyOperatorOfCancellation(outcome);
        } catch (RuntimeException e) {
            log.warn("Operator cancellation notice failed for request {}: {}",
                    outcome.request().id(), e.getMessage());
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Only this is stored, so the table cannot be used to confirm anything. */
    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private record Effect(Instant effectiveAt, boolean applied) {
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
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.billing().grace());
        List<Subscription> lapsed = new java.util.ArrayList<>(subscriptions.findLapsed(cutoff));
        lapsed.addAll(subscriptions.findEndedTrials(now));
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

    /**
     * An order refused because the account already has the Supporter plan for good.
     * An {@link IllegalStateException}, so callers that only show the message need
     * nothing new.
     */
    public static class AlreadySupporterException extends IllegalStateException {
        public static final String MESSAGE = "Your account already has the Supporter plan "
                + "permanently and at no cost, so there is nothing to order.";

        public AlreadySupporterException() {
            super(MESSAGE);
        }
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
    public record Outcome(CancellationRequest request, Instant effectiveAt, boolean applied,
                          Route route) {

        /** Declared and acted on by the signed-in owner. */
        public Outcome(CancellationRequest request, Instant effectiveAt, boolean applied) {
            this(request, effectiveAt, applied, Route.OWNER);
        }

        /** Recorded, but nothing changes until the account's owner confirms it. */
        public boolean awaitingOwner() {
            return route == Route.LINK_SENT || route == Route.RECORDED;
        }

        /**
         * Whether what was typed into the form may be repeated back. Not once the
         * declaration came from someone who was not signed in: the owner confirming
         * it by link did not necessarily write it.
         */
        public boolean echoDeclaration() {
            return route == Route.OWNER;
        }
    }

    /** How a declaration was handled. */
    public enum Route {

        /** From the signed-in owner of the account, and acted on straight away. */
        OWNER,

        /** From someone else; a confirmation link went to the account's address. */
        LINK_SENT,

        /**
         * From someone else, and no link was sent: no account with anything to
         * cancel, or that account was sent enough links already. On record for the
         * operator.
         */
        RECORDED,

        /** The account's owner followed the link, and it was acted on. */
        CONFIRMED_BY_LINK
    }
}
