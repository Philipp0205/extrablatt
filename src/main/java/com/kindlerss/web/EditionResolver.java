package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Decides which edition serves a request, and remembers an explicit choice.
 *
 * <p>The accessible edition normally has a host of its own, but it is also
 * reachable through {@code ?display=accessible} anywhere. That is not only a
 * development convenience: a reader who lands on the main host — from a link,
 * from an old bookmark, from a confirmation e-mail — can switch without having
 * to be told that a second subdomain exists.
 */
@Component
public class EditionResolver {

    /** Query parameter that switches edition and is remembered from then on. */
    public static final String PARAMETER = "display";

    /** Host-only cookie holding an explicit choice. Host-only matters: the two hosts do not share it. */
    public static final String COOKIE = "extrablatt-edition";

    /** Request attribute (and model attribute) carrying the resolved edition. */
    public static final String ATTRIBUTE = "edition";

    private static final int COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60;

    private final AppProperties properties;
    private final boolean secureCookies;

    public EditionResolver(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.secureCookies = environment.acceptsProfiles(Profiles.of("production"));
    }

    /**
     * An explicit {@code ?display=} wins over a remembered choice, which in turn
     * wins over the host: a reader who deliberately switched on one host should
     * stay switched there.
     */
    public Edition resolve(HttpServletRequest request) {
        Edition chosen = Edition.parse(request.getParameter(PARAMETER));
        if (chosen != null) {
            return chosen;
        }
        Edition remembered = fromCookie(request);
        if (remembered != null) {
            return remembered;
        }
        return matchesAccessibleHost(request) ? Edition.ACCESSIBLE : Edition.STANDARD;
    }

    /** True when this request asked for an edition explicitly, so the choice is worth storing. */
    public boolean isExplicitChoice(HttpServletRequest request) {
        return Edition.parse(request.getParameter(PARAMETER)) != null;
    }

    public void remember(HttpServletRequest request, HttpServletResponse response, Edition edition) {
        Cookie cookie = new Cookie(COOKIE, edition.value());
        cookie.setPath(contextPath(request));
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** The accessible edition's own host, or null when it has none configured. */
    public String accessibleBaseUrl() {
        return properties.accessibility().baseUrl();
    }

    private Edition fromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return Edition.parse(cookie.getValue());
            }
        }
        return null;
    }

    private boolean matchesAccessibleHost(HttpServletRequest request) {
        String configured = properties.accessibility().domain();
        if (configured == null) {
            return false;
        }
        String host = request.getServerName();
        return host != null && host.toLowerCase(Locale.ROOT).equals(configured);
    }

    private static String contextPath(HttpServletRequest request) {
        String path = request.getContextPath();
        return path == null || path.isBlank() ? "/" : path;
    }
}
