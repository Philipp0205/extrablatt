package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Whether this deployment charges for anything, published to every view.
 *
 * <p>Global rather than scoped to the signed-in controllers, because the pages that
 * need it most are the ones nobody has signed in to: § 312k Abs. 2 BGB wants the
 * cancellation route permanently available, and the login page is a page. A
 * self-hosted copy leaves the flag off and none of it appears.
 */
@ControllerAdvice
public class BillingAdvice {

    private final AppProperties properties;

    public BillingAdvice(AppProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute("billingEnabled")
    public boolean billingEnabled() {
        return properties.billing().enabled();
    }
}
