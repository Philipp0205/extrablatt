package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Publishes the signed-in account's e-mail and verification status to every view. */
@ControllerAdvice(assignableTypes = {AppController.class, SettingsController.class, AdminController.class})
public class AccountAdvice {

    private final CurrentUser currentUser;
    private final AppProperties properties;

    public AccountAdvice(CurrentUser currentUser, AppProperties properties) {
        this.currentUser = currentUser;
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
}
