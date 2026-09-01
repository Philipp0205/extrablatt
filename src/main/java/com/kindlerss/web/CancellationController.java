package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.CancellationRequest;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.SubscriptionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The cancellation route § 312k BGB requires, and the reason it is a page of its
 * own instead of a button in Settings.
 *
 * <p>The statute wants a permanently available, clearly labelled cancellation
 * button that leads straight to a confirmation page, and the OLG Köln held
 * (10 January 2025, 6 U 62/24) that the path may not sit behind account
 * credentials. Every payment provider's customer portal is reached by signing in or
 * through an e-mailed login link, so none of them satisfies this: the app needs its
 * own public route. The BGH closed the other escape hatch on 22 May 2025
 * (I ZR 161/24), holding that § 312k applies even where the consumer pays once and
 * the contract ends by itself — so the yearly plan needs this too.
 *
 * <p>The fields on the confirmation page are both a minimum and a maximum. The
 * statute lists what a consumer must be able to state, and asking for more than
 * that is itself a defect, which is why there is no password box here and no
 * "reason" that has to be filled in.
 */
@Controller
public class CancellationController {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final SubscriptionService subscriptions;
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public CancellationController(SubscriptionService subscriptions, CurrentUser currentUser,
                                  AppProperties properties) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /**
     * The confirmation page. Reached directly by the cancellation button, without a
     * login, and prefilled from the session only as a convenience when there is one.
     */
    @GetMapping("/cancel")
    public String form(Model model) {
        currentUser.details().ifPresent(details -> model.addAttribute("email", details.getUsername()));
        model.addAttribute("today", LocalDate.now(ZONE).toString());
        model.addAttribute("billingEnabled", properties.billing().enabled());
        return "cancel";
    }

    @PostMapping("/cancel")
    public String cancel(@RequestParam("email") String email,
                         @RequestParam(value = "name", required = false) String name,
                         @RequestParam(value = "contractRef", required = false) String contractRef,
                         @RequestParam(value = "kind", required = false) String kind,
                         @RequestParam(value = "requestedEnd", required = false) String requestedEnd,
                         @RequestParam(value = "reason", required = false) String reason,
                         Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("error", "Please give the e-mail address of the account.");
            model.addAttribute("today", LocalDate.now(ZONE).toString());
            model.addAttribute("billingEnabled", properties.billing().enabled());
            return "cancel";
        }
        SubscriptionService.Declaration declaration = new SubscriptionService.Declaration(
                email.trim().toLowerCase(),
                trimToNull(name),
                trimToNull(contractRef),
                CancellationRequest.Kind.parse(kind),
                parseDate(requestedEnd),
                trimToNull(reason));
        SubscriptionService.Outcome outcome = subscriptions.cancel(
                declaration, currentUser.details().map(details -> details.id()).orElse(null));

        // Shown as well as e-mailed: § 312k Abs. 3 BGB is about the consumer being
        // able to keep the declaration, and not everyone reads e-mail on the device
        // they cancelled from.
        model.addAttribute("request", outcome.request());
        model.addAttribute("receivedAt", STAMP.format(outcome.request().receivedAt().atZone(ZONE)));
        model.addAttribute("effectiveAt", DAY.format(outcome.effectiveAt().atZone(ZONE)));
        model.addAttribute("applied", outcome.applied());
        model.addAttribute("immediate",
                outcome.request().kind() == CancellationRequest.Kind.IMMEDIATE);
        return "cancel-confirmed";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
