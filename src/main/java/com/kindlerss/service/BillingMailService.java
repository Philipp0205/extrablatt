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
     * Confirms a cancellation with everything § 312k Abs. 4 BGB asks for, and gives
     * the reader a copy they can keep, which is what Abs. 3 is about.
     */
    public void sendCancellationConfirmation(String toEmail, SubscriptionService.Outcome outcome) {
        CancellationRequest request = outcome.request();
        boolean immediate = request.kind() == CancellationRequest.Kind.IMMEDIATE;
        String ends = outcome.applied()
                ? DAY.format(outcome.effectiveAt().atZone(ZONE))
                : DAY.format(outcome.effectiveAt().atZone(ZONE)) + " (being processed — see below)";
        String body = """
                We have received your cancellation. This message is your confirmation;
                keep it for your records.

                Received:        %s (Central European Time)
                Declared by:     %s
                Account:         %s
                Contract:        %s
                Type:            %s
                Contract ends:   %s
                Reason given:    %s

                %s

                Nothing you have saved is deleted. Your feeds, articles and reading
                position stay exactly as they are, and your account keeps working on the
                free plan. If you subscribe again later, everything is where you left it.

                If any of the above is wrong, reply to this message and we will correct it.
                """.formatted(
                STAMP.format(request.receivedAt().atZone(ZONE)),
                blankAs(request.name(), "not given"),
                request.email(),
                blankAs(request.contractRef(), "Extrablatt Supporter"),
                immediate ? "Immediate (extraordinary) cancellation" : "Ordinary cancellation",
                ends,
                blankAs(request.reason(), "none"),
                outcome.applied()
                        ? "Your subscription will not renew. No further payment will be taken."
                        : "Your declaration is on record and has been passed to the operator, "
                                + "who will stop any further payment and contact you if a refund "
                                + "is due.");
        accountMailService.send(toEmail, "Your Extrablatt cancellation — confirmation", body);
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
                    + "so nobody was told to stop the payment", outcome.request().id());
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
                Applied here:  %s
                Contract ends: %s
                Reason:        %s

                Cancel the subscription at the payment provider so no further payment is
                taken. The app has already %s.
                """.formatted(
                request.id(),
                STAMP.format(request.receivedAt().atZone(ZONE)),
                request.email(),
                request.userId() == null ? "no" : "yes (account " + request.userId() + ")",
                request.kind(),
                outcome.applied() ? "yes" : "NO — needs manual handling",
                DAY.format(outcome.effectiveAt().atZone(ZONE)),
                blankAs(request.reason(), "none"),
                outcome.applied()
                        ? "recorded the cancellation and stopped granting the paid plan past that date"
                        : "recorded the declaration only");
        accountMailService.send(operator, "Extrablatt: cancellation received", body);
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
