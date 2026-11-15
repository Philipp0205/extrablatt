package com.kindlerss.security;

import com.kindlerss.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Loads accounts from the database by e-mail for form login and remember-me. */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AdminAccess adminAccess;

    public DatabaseUserDetailsService(UserRepository userRepository, AdminAccess adminAccess) {
        this.userRepository = userRepository;
        this.adminAccess = adminAccess;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmail(username)
                .map(user -> new AppUserDetails(user, adminAccess.isAdmin(user.email())))
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + username));
    }

    /** The account a recovery code hash belongs to, loaded the same way as by e-mail. */
    public Optional<UserDetails> findByRecoveryHash(String recoveryHash) {
        return userRepository.findByRecoveryHash(recoveryHash)
                .map(user -> new AppUserDetails(user, adminAccess.isAdmin(user.email())));
    }
}
