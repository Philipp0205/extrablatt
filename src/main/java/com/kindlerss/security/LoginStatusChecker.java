package com.kindlerss.security;

import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;

/**
 * Account status checks for form login, run only after the password has been
 * verified. Spring's default runs them before, which let anyone who typed a
 * registered but unconfirmed address, with any password at all, learn that the
 * account exists and is waiting for confirmation.
 *
 * <p>An account switched off by an operator fails like a wrong password; only an
 * unconfirmed mailbox gets its own {@link EmailNotVerifiedException}.
 */
public final class LoginStatusChecker implements UserDetailsChecker {

    @Override
    public void check(UserDetails user) {
        if (!user.isAccountNonLocked()) {
            throw new LockedException("Account is locked");
        }
        if (!user.isAccountNonExpired()) {
            throw new AccountExpiredException("Account has expired");
        }
        if (user instanceof AppUserDetails details) {
            if (!details.accountEnabled()) {
                throw new DisabledException("Account is disabled");
            }
            if (!details.emailVerified()) {
                throw new EmailNotVerifiedException("E-mail address not confirmed");
            }
        } else if (!user.isEnabled()) {
            throw new DisabledException("Account is disabled");
        }
        if (!user.isCredentialsNonExpired()) {
            throw new CredentialsExpiredException("Credentials have expired");
        }
    }
}
