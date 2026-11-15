package com.kindlerss.security;

import com.kindlerss.service.RecoveryCodeService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;
import java.util.function.Function;

/**
 * Signs in the account whose recovery code was typed. Anything that is not a code, and
 * any code no account has, fails with the same {@link BadCredentialsException}. Only
 * once a code has matched is the account's status checked, with the same
 * {@link LoginStatusChecker} as password login, so an account that is switched off fails
 * like a wrong code and an unconfirmed one gets the same "unverified" answer.
 */
public class RecoveryCodeAuthenticationProvider implements AuthenticationProvider {

    private final Function<String, Optional<UserDetails>> findByRecoveryHash;
    private final LoginStatusChecker statusChecker = new LoginStatusChecker();

    public RecoveryCodeAuthenticationProvider(Function<String, Optional<UserDetails>> findByRecoveryHash) {
        this.findByRecoveryHash = findByRecoveryHash;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String typed = authentication.getCredentials() instanceof String s ? s : null;
        String normalized = RecoveryCodeService.normalize(typed)
                .orElseThrow(RecoveryCodeAuthenticationProvider::invalid);
        UserDetails user = findByRecoveryHash.apply(RecoveryCodeService.hash(normalized))
                .orElseThrow(RecoveryCodeAuthenticationProvider::invalid);
        statusChecker.check(user);
        RecoveryCodeAuthenticationToken result = RecoveryCodeAuthenticationToken.authenticated(user);
        result.setDetails(authentication.getDetails());
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return RecoveryCodeAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static BadCredentialsException invalid() {
        return new BadCredentialsException("Invalid recovery code");
    }
}
