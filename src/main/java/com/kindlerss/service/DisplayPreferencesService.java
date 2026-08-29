package com.kindlerss.service;

import com.kindlerss.domain.DisplayPreferences;
import com.kindlerss.repository.DisplayPreferencesRepository;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads and stores a reader's display settings.
 *
 * <p>The cookie is what actually renders a page: it is there before anyone logs
 * in, so the login form itself already arrives in the reader's own colours and
 * type size. The database copy exists so that signing in on a new device does
 * not mean setting everything up again in a format one cannot yet read.
 */
@Service
public class DisplayPreferencesService {

    public static final String COOKIE = "extrablatt-display";

    private static final int COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60;

    /** Set once per request after the cookie is read, so later reads in the same request agree. */
    private static final String REQUEST_ATTRIBUTE = DisplayPreferencesService.class.getName();

    private final DisplayPreferencesRepository repository;
    private final CurrentUser currentUser;
    private final boolean secureCookies;

    public DisplayPreferencesService(DisplayPreferencesRepository repository,
                                     CurrentUser currentUser,
                                     Environment environment) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.secureCookies = environment.acceptsProfiles(Profiles.of("production"));
    }

    /**
     * The settings already resolved for this request, or the defaults. For the one
     * caller that cannot reach this service — the exception handler, which runs
     * after the interceptors have finished — rather than resolving them again.
     */
    public static DisplayPreferences resolved(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        return cached instanceof DisplayPreferences preferences
                ? preferences : DisplayPreferences.DEFAULTS;
    }

    public DisplayPreferences forRequest(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        if (cached instanceof DisplayPreferences preferences) {
            return preferences;
        }
        DisplayPreferences resolved = readCookie(request)
                .or(this::storedForCurrentUser)
                .orElse(DisplayPreferences.DEFAULTS);
        request.setAttribute(REQUEST_ATTRIBUTE, resolved);
        return resolved;
    }

    /**
     * Stores the settings in both places. A failure to reach the database must not
     * lose the change the reader just made in front of them, so the cookie is
     * written first and the account copy is best effort.
     */
    public void save(HttpServletRequest request, HttpServletResponse response,
                     DisplayPreferences preferences) {
        writeCookie(request, response, preferences);
        request.setAttribute(REQUEST_ATTRIBUTE, preferences);
        currentUser.details().map(AppUserDetails::id)
                .ifPresent(userId -> repository.save(userId, preferences));
    }

    private Optional<DisplayPreferences> storedForCurrentUser() {
        return currentUser.details().map(AppUserDetails::id).flatMap(repository::find);
    }

    private Optional<DisplayPreferences> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(DisplayPreferences.decode(cookie.getValue()));
            }
        }
        return Optional.empty();
    }

    private void writeCookie(HttpServletRequest request, HttpServletResponse response,
                             DisplayPreferences preferences) {
        Cookie cookie = new Cookie(COOKIE, preferences.encode());
        String contextPath = request.getContextPath();
        cookie.setPath(contextPath == null || contextPath.isBlank() ? "/" : contextPath);
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
