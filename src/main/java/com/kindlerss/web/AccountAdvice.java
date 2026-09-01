package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.EntitlementService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Publishes the signed-in account's e-mail and verification status to every view. */
@ControllerAdvice(assignableTypes = {AppController.class, SettingsController.class, AdminController.class,
        BillingController.class})
public class AccountAdvice {

    private final CurrentUser currentUser;
    private final EntitlementService entitlements;
    private final AppProperties properties;

    public AccountAdvice(CurrentUser currentUser, EntitlementService entitlements,
                         AppProperties properties) {
        this.currentUser = currentUser;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    @ModelAttribute("accountEmail")
    public String accountEmail() {
        return currentUser.details().map(AppUserDetails::getUsername).orElse(null);
    }

    @ModelAttribute("emailVerified")
    public boolean emailVerified() {
        return currentUser.details().map(AppUserDetails::emailVerified).orElse(false);
    }

    @ModelAttribute("admin")
    public boolean admin() {
        return currentUser.details().map(AppUserDetails::admin).orElse(false);
    }

    /**
     * Whether the account is on the paid plan (including a live trial). False for
     * an expired trial, and also false for nobody signed in.
     */
    @ModelAttribute("supporter")
    public boolean supporter() {
        Entitlement entitlement = entitlement();
        return entitlement != null && entitlement.paid();
    }

    @ModelAttribute("onTrial")
    public boolean onTrial() {
        Entitlement entitlement = entitlement();
        return entitlement != null && entitlement.onTrial();
    }

    @ModelAttribute("trialEndsOn")
    public String trialEndsOn() {
        Entitlement entitlement = entitlement();
        if (entitlement == null || entitlement.trialEndsAt() == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
                .format(entitlement.trialEndsAt().atZone(ZoneId.of("Europe/Berlin")));
    }

    private Entitlement entitlement() {
        if (!properties.billing().enabled()) {
            return new Entitlement(com.kindlerss.domain.Plan.SUPPORTER, 50, 0, 50, true);
        }
        return currentUser.details()
                .map(details -> entitlements.forUser(details.id()))
                .orElse(null);
    }
}
