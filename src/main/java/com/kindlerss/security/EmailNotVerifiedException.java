package com.kindlerss.security;

import org.springframework.security.authentication.DisabledException;

/**
 * The password was right, but the mailbox behind the account has not been
 * confirmed yet. Only thrown after the password check, so the login page can say
 * "confirm your e-mail" without telling a stranger which addresses are registered.
 */
public class EmailNotVerifiedException extends DisabledException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
