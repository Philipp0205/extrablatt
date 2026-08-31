package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.EntitlementService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Publishes the signed-in account's e-mail and verification status to every view. */
@ControllerAdvice(assignableTypes = {AppController.class, SettingsController.class, AdminController.class,
        AccessibleController.class, BillingController.class})
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

    /** PayPal.me link shown in Settings and in the occasional donation reminder. */
    @ModelAttribute("donateUrl")
    public String donateUrl() {
        return properties.donateUrl();
    }

    /**
     * Whether the account is on the paid plan. False for a free account, and also
     * false for nobody signed in, so a template can ask without checking twice.
     */
    @ModelAttribute("supporter")
    public boolean supporter() {
        if (!properties.billing().enabled()) {
            return true;
        }
        return currentUser.details()
                .map(details -> entitlements.forUser(details.id()).paid())
                .orElse(false);
    }
}
