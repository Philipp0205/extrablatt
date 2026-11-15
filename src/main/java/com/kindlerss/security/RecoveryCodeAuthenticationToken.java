package com.kindlerss.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * A recovery code on its way to {@link RecoveryCodeAuthenticationProvider}, and the
 * signed-in account once the provider has accepted it. The typed code is only ever the
 * credentials of the unauthenticated form; it is dropped from the authenticated one.
 */
public class RecoveryCodeAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private final String code;

    /** What the login form submitted, not yet checked. */
    public RecoveryCodeAuthenticationToken(String code) {
        super(null);
        this.principal = null;
        this.code = code;
        setAuthenticated(false);
    }

    private RecoveryCodeAuthenticationToken(UserDetails user, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = user;
        this.code = null;
        super.setAuthenticated(true);
    }

    static RecoveryCodeAuthenticationToken authenticated(UserDetails user) {
        return new RecoveryCodeAuthenticationToken(user, user.getAuthorities());
    }

    @Override
    public Object getCredentials() {
        return code;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
