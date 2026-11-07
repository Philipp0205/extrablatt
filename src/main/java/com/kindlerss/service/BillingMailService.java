package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.CancellationRequest;
import com.kindlerss.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The e-mail that goes with a subscription. The cancellation confirmation is not
 * a courtesy: § 312k Abs. 4 BGB requires the content of the declaration, the date
 * and time it arrived and the moment the contract ends to be confirmed in text
 * form, immediately and electronically. Sending it is part of the cancellation,
 * which is why it lives beside the state change rather than in a notification
 * afterthought.
 */
@Service
public class BillingMailService {

    private static final Logger log = LoggerFactory.getLogger(BillingMailService.class);

    /**
     * Times are stated in Central European Time. A German consumer's cancellation
     * notice is read against German dates, and "14:03 UTC" invites the reader to do
     * the arithmetic themselves.
     */
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final AccountMailService accountMailService;
    private final AppProperties properties;

    public BillingMailService(AccountMailService accountMailService, AppProperties properties) {
        this.accountMailService = accountMailService;
        this.properties = properties;
    }

    public void sendSubscriptionConfirmation(String toEmail, Subscription subscription) {
        AppProperties.Billing billing = properties.billing();
        boolean monthly = subscription.interval() != null && subscription.interval().isMonthly();
        String price = monthly
                ? Money.priceTag(billing.monthlyPriceCents()) + " per month"
                : Money.priceTag(billing.yearlyPriceCents()) + " per year";
        String renews = subscription.currentPeriodEnd() == null
                ? "the end of the period you paid for"
                : DAY.format(subscription.currentPeriodEnd().atZone(ZONE));
        String body = """
                Thank you — your Extrablatt subscription is active.

                Plan:     Supporter (%s)
                Renews:   %s

                Your full allowances are already in effect. You can see your
                subscription, and cancel it at any time, under Settings.

                Cancel here at any time: %s/cancel

                Right of withdrawal: you have 14 days from today to withdraw from this
                contract. If you do, we refund the part of the period you have not used.
                The details are at %s/withdrawal.
                """.formatted(price, renews, publicUrl(), publicUrl());
        accountMailService.send(toEmail, "Your Extrablatt subscription", body);
    }

    /**
     * Asks the account's owner to confirm a cancellation someone declared without
     * signing in. Goes only to the account's own address, and says nothing the
     * requester typed apart from the type of cancellation, so the public form cannot
     * be used to put someone else's words in front of a stranger.
     */
    public void sendCancellationLink(String toEmail, CancellationRequest request, String token) {
        boolean immediate = request.kind() == CancellationRequest.Kind.IMMEDIATE;
        String body = """
                Someone asked to cancel the Extrablatt subscription of this account,
                using the public cancellation form without signing in.

                Received:  %s (Central European Time)
                Type:      %s

                If that was you, confirm it here and the cancellation takes effect:

                %s

                The link works once, for the next 24 hours. If you did not ask for this,
                ignore this message: nothing changes, and your subscription carries on.
                """.formatted(
                STAMP.format(request.receivedAt().atZone(ZONE)),
                immediate ? "Immediate (extraordinary) cancellation" : "Ordinary cancellation",
                publicUrl() + "/cancel/confirm?token=" + token);
        accountMailService.send(toEmail, "Confirm your Extrablatt cancellation", body);
    }

    /**
     * Confirms a cancellation with everything § 312k Abs. 4 BGB asks for, and gives
     * the reader a copy they can keep, which is what Abs. 3 is about. What was typed
     * into the form is only repeated when the signed-in owner typed it.
     */
    public void sendCancellationConfirmation(String toEmail, SubscriptionService.Outcome outcome) {
        CancellationRequest request = outcome.request();
        boolean immediate = request.kind() == CancellationRequest.Kind.IMMEDIATE;
        String ends = outcome.applied()
                ? day(outcome.effectiveAt())
                : day(outcome.effectiveAt()) + " (being processed, see below)";
        String declared = outcome.echoDeclaration()
                ? """
                Declared by:     %s
                Contract:        %s
                """.formatted(blankAs(request.name(), "not given"),
                        blankAs(request.contractRef(), "Extrablatt Supporter"))
                : "Contract:        Extrablatt Supporter\n";
        String reason = outcome.echoDeclaration()
                ? "Reason given:    " + blankAs(request.reason(), "none") + "\n"
                : "";
        String body = """
                We have received your cancellation. This message is your confirmation;
                keep it for your records.

                Received:        %s (Central European Time)
                Account:         %s
                %sType:            %s
                Contract ends:   %s
                %s
                %s

                Nothing you have saved is deleted. Your feeds, articles and reading
                position stay exactly as they are. Reading in the browser continues.
                If you subscribe again later, everything is where you left it.

                If any of the above is wrong, reply to this message and we will correct it.
                """.formatted(
                STAMP.format(request.receivedAt().atZone(ZONE)),
                request.email(),
                declared,
                immediate ? "Immediate (extraordinary) cancellation" : "Ordinary cancellation",
                ends,
                reason,
                outcome.applied()
                        ? "Your subscription will not renew. No further payment will be taken."
                        : "Your declaration is on record and has been passed to the operator, "
                                + "who will stop any further payment and contact you if a refund "
                                + "is due.");
        accountMailService.send(toEmail, "Your Extrablatt cancellation: confirmation", body);
    }

    /**
     * Tells the operator to stop the money at the provider. The app deliberately
     * holds no provider API key, so this notice is the step that ends the billing
     * relationship rather than merely the contract.
     */
    public void notifyOperatorOfCancellation(SubscriptionService.Outcome outcome) {
        String operator = properties.billing().operatorEmail();
        if (operator == null) {
            log.warn("Cancellation {} recorded but app.billing.operator-email is unset, "
                    + "so nobody was told about it", outcome.request().id());
            return;
        }
        CancellationRequest request = outcome.request();
        String body = """
                A cancellation was received through /cancel.

                Request id:    %d
                Received:      %s
                E-mail:        %s
                Account found: %s
                Type:          %s
                Handled as:    %s
                Applied here:  %s
                Contract ends: %s
                Reason:        %s

                %s
                """.formatted(
                request.id(),
                STAMP.format(request.receivedAt().atZone(ZONE)),
                request.email(),
                request.userId() == null ? "no" : "yes (account " + request.userId() + ")",
                request.kind(),
                routeLabel(outcome.route()),
                outcome.applied() ? "yes" : "no",
                outcome.effectiveAt() == null ? "not yet known" : day(outcome.effectiveAt()),
                blankAs(request.reason(), "none"),
                operatorAction(outcome));
        String subject = outcome.awaitingOwner()
                ? "Extrablatt: cancellation declared, not yet confirmed"
                : "Extrablatt: cancellation received";
        accountMailService.send(operator, subject, body);
    }

    private static String routeLabel(SubscriptionService.Route route) {
        return switch (route) {
            case OWNER -> "declared by the signed-in owner";
            case CONFIRMED_BY_LINK -> "confirmed by the owner through the e-mailed link";
            case LINK_SENT -> "declared without signing in; confirmation link sent to the account";
            case RECORDED -> "declared without signing in; no link sent (no paid account "
                    + "for this address, or the daily link limit was reached)";
        };
    }

    private static String operatorAction(SubscriptionService.Outcome outcome) {
        if (outcome.route() == SubscriptionService.Route.LINK_SENT) {
            return "Nothing to do yet. If the owner confirms, you get a second notice.";
        }
        if (outcome.route() == SubscriptionService.Route.RECORDED) {
            return "The app changed nothing. If this is a genuine declaration for a paying "
                    + "customer, handle it by hand and confirm it to them in text form.";
        }
        return outcome.applied()
                ? "Cancel the subscription at the payment provider so no further payment is "
                        + "taken. The app has already recorded the cancellation and stopped "
                        + "granting the paid plan past that date."
                : "Cancel the subscription at the payment provider so no further payment is "
                        + "taken. The app has recorded the declaration only; it needs manual "
                        + "handling.";
    }

    private static String day(Instant instant) {
        return DAY.format(instant.atZone(ZONE));
    }

    private String publicUrl() {
        return properties.publicUrl();
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String stamp(Instant instant) {
        return STAMP.format(instant.atZone(ZONE));
    }
}
