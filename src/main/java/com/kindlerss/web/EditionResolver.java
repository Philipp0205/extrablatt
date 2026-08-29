package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
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

    /**
     * First label of the host the accessible edition is expected on when no domain
     * is configured. Forgetting the setting is otherwise a silent failure: the
     * subdomain resolves, answers, and serves the very edition the reader who
     * needed it cannot use.
     */
    private static final String CONVENTIONAL_SUBDOMAIN = "accessibility.";

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

    /**
     * Where "Standard version" should lead from a page of the accessible edition:
     * the standard edition's own host, or null to switch in place instead.
     *
     * <p>Switching in place is right on a host the two editions share, but wrong on
     * the accessible edition's own host, where it leaves a year-long cookie that
     * makes that host serve the standard edition from then on. The subdomain then
     * goes on being called accessibility and quietly doing the opposite — and the
     * reader who cannot read what it now serves is the least able to work out why.
     */
    public String standardEditionUrl(HttpServletRequest request) {
        if (!matchesAccessibleHost(request)) {
            return null;
        }
        String standard = properties.publicUrl();
        String host = hostOf(standard);
        if (host == null || host.equalsIgnoreCase(request.getServerName())) {
            return null;
        }
        return standard;
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
        String host = request.getServerName();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        String configured = properties.accessibility().domain();
        return configured != null ? host.equals(configured) : host.startsWith(CONVENTIONAL_SUBDOMAIN);
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String contextPath(HttpServletRequest request) {
        String path = request.getContextPath();
        return path == null || path.isBlank() ? "/" : path;
    }
}
