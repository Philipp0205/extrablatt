package com.kindlerss.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The address the imprint, privacy notice and withdrawal page publish, in one place
 * and settable without a code change.
 *
 * <p>§ 5 Abs. 1 Nr. 2 DDG asks for an e-mail address that is really read, which makes
 * an address that hard-bounces worse than a less pretty one that arrives. The default
 * `hello@extrablatt.app` depends on the forward described in `docs/going-live.md`
 * existing; until it does, `CONTACT_EMAIL` can name the mailbox behind it directly.
 *
 * <p>Global rather than scoped to a controller, like {@link BillingAdvice}: the pages
 * that need it are the ones nobody has signed in to.
 */
@ControllerAdvice
public class ContactAdvice {

    static final String DEFAULT_CONTACT_EMAIL = "hello@extrablatt.app";

    private final String contactEmail;

    public ContactAdvice(@Value("${app.contact-email:}") String configured) {
        this.contactEmail = configured == null || configured.isBlank()
                ? DEFAULT_CONTACT_EMAIL
                : configured.trim();
    }

    @ModelAttribute("contactEmail")
    public String contactEmail() {
        return contactEmail;
    }
}
