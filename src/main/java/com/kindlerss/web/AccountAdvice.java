package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Publishes the signed-in account's e-mail and verification status to every view. */
@ControllerAdvice(assignableTypes = {AppController.class, SettingsController.class, AdminController.class,
        BillingController.class})
public class AccountAdvice {

    /**
     * Set on the session when the reader closes the post-trial notice. A new login
     * starts a new session, so the notice returns the next time they sign in.
     */
    public static final String TRIAL_ENDED_ACK = "TRIAL_ENDED_ACK";

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

    /**
     * After the complimentary week, every new login opens a notice until they
     * close it or go to subscribe. Hidden on the subscription and checkout pages
     * so those screens are not covered by the same message.
     */
    @ModelAttribute("trialEndedPrompt")
    public boolean trialEndedPrompt() {
        Entitlement entitlement = entitlement();
        if (entitlement == null || !entitlement.trialExpired()) {
            return false;
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/settings/subscription") || uri.startsWith("/billing"))) {
            return false;
        }
        HttpSession session = request.getSession(false);
        return session == null || session.getAttribute(TRIAL_ENDED_ACK) == null;
    }

    @ModelAttribute("trialEndedRedirect")
    public String trialEndedRedirect() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "/";
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        String query = request.getQueryString();
        return query == null || query.isBlank() ? uri : uri + "?" + query;
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        return attrs.getRequest();
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
