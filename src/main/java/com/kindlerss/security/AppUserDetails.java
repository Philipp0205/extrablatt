package com.kindlerss.security;

import com.kindlerss.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Spring Security principal that also carries the database user id and status. */
public class AppUserDetails implements UserDetails {

    private final long id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean emailVerified;
    private final boolean admin;

    public AppUserDetails(AppUser user) {
        this(user, false);
    }

    public AppUserDetails(AppUser user, boolean admin) {
        this.id = user.id();
        this.email = user.email();
        this.passwordHash = user.passwordHash();
        this.enabled = user.enabled();
        this.emailVerified = user.emailVerified();
        this.admin = admin;
    }

    public long id() {
        return id;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public boolean admin() {
        return admin;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"),
                          new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Owning the password is not enough to activate an account: the mailbox
        // must have been confirmed before Spring Security creates a session.
        return enabled && emailVerified;
    }
}
