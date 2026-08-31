package com.kindlerss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.BillingEventRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.security.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Turns a signed provider callback into a subscription change.
 *
 * <p>Three things have to be true of this class and none of them are obvious from
 * the outside. It authenticates the caller itself, because the endpoint has no
 * session. It is idempotent, because both providers retry and occasionally deliver
 * the same event twice, and a second application of "renewed" would hand out a
 * second period. And it is the only route by which anyone becomes a subscriber:
 * the page a reader lands on after paying grants nothing at all, so closing the
 * tab costs them nothing and forging the URL gains them nothing.
 */
@Service
public class BillingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookService.class);

    public enum Result {
        /** Applied, or recognised as one we have already applied. */
        ACCEPTED,
        /** Signature missing, stale or wrong. */
        REJECTED,
        /** Authentic, but about something this app does not track. */
        IGNORED,
        /** Authentic and about a subscription, but it could not be applied. */
        FAILED
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final BillingEventRepository events;
    private final SubscriptionService subscriptions;
    private final UserRepository users;
    private final AppProperties properties;

    public BillingWebhookService(BillingEventRepository events,
                                 SubscriptionService subscriptions,
                                 UserRepository users,
                                 AppProperties properties) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.users = users;
        this.properties = properties;
    }

    public boolean configured() {
        return properties.billing().webhookConfigured();
    }

    public Result handle(String signatureHeader, byte[] body) {
        AppProperties.Billing billing = properties.billing();
        if (!billing.webhookConfigured()) {
            return Result.REJECTED;
        }
        String provider = billing.provider();
        boolean valid = switch (provider) {
            case "stripe" -> WebhookSignature.verifyStripe(signatureHeader, body,
                    billing.webhookSecret(), Instant.now());
            case "paddle" -> WebhookSignature.verifyPaddle(signatureHeader, body,
                    billing.webhookSecret(), Instant.now());
            default -> false;
        };
        if (!valid) {
            log.warn("Rejected a {} webhook: signature missing, stale or wrong", provider);
            return Result.REJECTED;
        }

        JsonNode root;
        try {
            root = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Rejected a {} webhook: body is not JSON", provider);
            return Result.REJECTED;
        }

        Optional<ProviderSubscriptionUpdate> parsed = "stripe".equals(provider)
                ? parseStripe(root)
                : parsePaddle(root);
        if (parsed.isEmpty()) {
            return Result.IGNORED;
        }
        ProviderSubscriptionUpdate update = parsed.get();

        // The insert is the lock: two concurrent deliveries of one event cannot both
        // win the primary key, so only one of them goes on to apply it.
        if (!events.claim(update.eventId(), provider, update.type(),
                new String(body, StandardCharsets.UTF_8))) {
            log.info("Ignoring {} {}: already seen", provider, update.eventId());
            return Result.ACCEPTED;
        }
        try {
            subscriptions.applyProviderUpdate(update);
            events.markProcessed(update.eventId());
            return Result.ACCEPTED;
        } catch (RuntimeException e) {
            // Recorded rather than rethrown: a 500 makes the provider retry an event
            // that will fail again, and the failure needs to be visible, not repeated.
            log.error("Could not apply {} {}: {}", provider, update.eventId(), e.getMessage());
            events.markFailed(update.eventId(), e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * Stripe. {@code checkout.session.completed} ties the provider's customer and
     * subscription ids to an account and opens access immediately, without a period
     * end — the subscription events that follow supply that.
     */
    private Optional<ProviderSubscriptionUpdate> parseStripe(JsonNode root) {
        String eventId = text(root, "id");
        String type = text(root, "type");
        JsonNode object = root.path("data").path("object");
        if (eventId == null || type == null || object.isMissingNode()) {
            return Optional.empty();
        }
        return switch (type) {
            case "checkout.session.completed" -> Optional.of(new ProviderSubscriptionUpdate(
                    eventId, type,
                    accountFor(text(object, "client_reference_id"),
                            firstText(object, "customer_email",
                                    object.path("customer_details").path("email").asText(null))),
                    text(object, "customer"),
                    text(object, "subscription"),
                    SubscriptionStatus.ACTIVE,
                    null, false, null));
            case "customer.subscription.created", "customer.subscription.updated",
                 "customer.subscription.deleted" -> Optional.of(new ProviderSubscriptionUpdate(
                    eventId, type,
                    accountFor(object.path("metadata").path("user_id").asText(null), null),
                    text(object, "customer"),
                    text(object, "id"),
                    stripeStatus(text(object, "status")),
                    stripePeriodEnd(object),
                    object.path("cancel_at_period_end").asBoolean(false),
                    stripeInterval(object)));
            default -> Optional.empty();
        };
    }

    /** Paddle Billing. Timestamps are ISO-8601 and the account reference is custom data. */
    private Optional<ProviderSubscriptionUpdate> parsePaddle(JsonNode root) {
        String eventId = text(root, "event_id");
        String type = text(root, "event_type");
        JsonNode data = root.path("data");
        if (eventId == null || type == null || data.isMissingNode()) {
            return Optional.empty();
        }
        if (!type.startsWith("subscription.")) {
            return Optional.empty();
        }
        JsonNode scheduledChange = data.path("scheduled_change");
        boolean cancelScheduled = "cancel".equalsIgnoreCase(scheduledChange.path("action").asText(""));
        return Optional.of(new ProviderSubscriptionUpdate(
                eventId, type,
                accountFor(firstText(data.path("custom_data"), "user_id", null), null),
                text(data, "customer_id"),
                text(data, "id"),
                paddleStatus(text(data, "status"), type),
                isoInstant(data.path("current_billing_period").path("ends_at").asText(null)),
                cancelScheduled,
                paddleInterval(data)));
    }

    /**
     * Which account a payment belongs to. The reference sent into the checkout comes
     * back first; a customer e-mail is the fallback, because a reader who pays
     * through a link they kept, or a provider whose parameter was misconfigured,
     * should still end up subscribed rather than charged for nothing.
     */
    private Long accountFor(String reference, String email) {
        if (reference != null && !reference.isBlank()) {
            try {
                return Long.parseLong(reference.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Checkout reference '{}' is not an account id", reference);
            }
        }
        if (email != null && !email.isBlank()) {
            return users.findByEmail(email).map(com.kindlerss.domain.AppUser::id).orElse(null);
        }
        return null;
    }

    private static SubscriptionStatus stripeStatus(String status) {
        if (status == null) {
            return SubscriptionStatus.PENDING;
        }
        return switch (status) {
            case "active", "trialing" -> SubscriptionStatus.ACTIVE;
            case "past_due", "unpaid", "paused" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELED;
            case "incomplete_expired" -> SubscriptionStatus.EXPIRED;
            default -> SubscriptionStatus.PENDING;
        };
    }

    private static SubscriptionStatus paddleStatus(String status, String type) {
        if ("subscription.canceled".equals(type)) {
            return SubscriptionStatus.CANCELED;
        }
        if (status == null) {
            return SubscriptionStatus.PENDING;
        }
        return switch (status) {
            case "active", "trialing" -> SubscriptionStatus.ACTIVE;
            case "past_due", "paused" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.PENDING;
        };
    }

    /**
     * Newer Stripe API versions moved the period end onto each subscription item, so
     * both places are read rather than assuming one API version.
     */
    private static Instant stripePeriodEnd(JsonNode object) {
        long epoch = object.path("current_period_end").asLong(0);
        if (epoch <= 0) {
            epoch = object.path("items").path("data").path(0).path("current_period_end").asLong(0);
        }
        return epoch <= 0 ? null : Instant.ofEpochSecond(epoch);
    }

    private static BillingInterval stripeInterval(JsonNode object) {
        JsonNode item = object.path("items").path("data").path(0);
        String interval = firstText(item.path("price").path("recurring"), "interval",
                item.path("plan").path("interval").asText(null));
        return interval == null ? null : "month".equalsIgnoreCase(interval)
                ? BillingInterval.MONTHLY : BillingInterval.YEARLY;
    }

    private static BillingInterval paddleInterval(JsonNode data) {
        String interval = data.path("items").path(0).path("price")
                .path("billing_cycle").path("interval").asText(null);
        return interval == null ? null : "month".equalsIgnoreCase(interval)
                ? BillingInterval.MONTHLY : BillingInterval.YEARLY;
    }

    private static Instant isoInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstText(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value != null ? value : (fallback == null || fallback.isBlank() ? null : fallback);
    }
}
