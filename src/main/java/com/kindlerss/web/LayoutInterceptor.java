package com.kindlerss.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Tells every page whether to render the e-ink, page-turning layout.
 *
 * <p>The answer is put on the request as {@code einkLayout} (a boolean), which
 * Thymeleaf reads like a model attribute, and {@code layoutPreference}
 * ({@code eink}, {@code scroll} or {@code auto}) for the Settings page. An
 * interceptor rather than a {@code @ControllerAdvice}, so the error page and
 * every controller get it too.
 *
 * <p>Order of precedence:
 * <ol>
 *   <li>{@code ?layout=kindle} or {@code ?layout=eink}, {@code ?layout=scroll}, on a GET:
 *       stored in the {@code layout} cookie for a year and used at once.
 *       {@code ?layout=auto} deletes the cookie. Other values are ignored.</li>
 *   <li>The {@code layout} cookie: {@code eink}, {@code scroll}, or {@code eink-auto}
 *       (written by {@code app.js} when the browser reports a slow-updating screen).</li>
 *   <li>The User-Agent ({@link DeviceLayout#isEink}).</li>
 * </ol>
 *
 * <p>The markup depends on the User-Agent and the cookie, so every response says
 * {@code Vary: User-Agent, Cookie}. Pages are already {@code no-store}; this keeps a
 * cache that ignores that from handing a phone the Kindle page.
 *
 * <p>The e-ink reading type (text size and face) is a second cookie, {@code eink-type},
 * set by {@code ?text=} and {@code ?font=}; see {@link EinkType}.
 *
 * <p>The cookie is not HttpOnly on purpose: {@code app.js} reads it so that a reader
 * who chose scrolling is not switched to pages by the browser-side fallback.
 */
public class LayoutInterceptor implements HandlerInterceptor {

    public static final String COOKIE = "layout";
    public static final String PARAMETER = "layout";
    public static final String EINK_ATTRIBUTE = "einkLayout";
    public static final String PREFERENCE_ATTRIBUTE = "layoutPreference";
    public static final String TYPE_COOKIE = "eink-type";
    public static final String SIZE_PARAMETER = "text";
    public static final String FACE_PARAMETER = "font";
    public static final String TYPE_CLASSES_ATTRIBUTE = "einkTypeClasses";
    public static final String TYPE_SIZE_ATTRIBUTE = "einkTypeSize";
    public static final String TYPE_FACE_ATTRIBUTE = "einkTypeFace";
    static final Duration COOKIE_LIFETIME = Duration.ofDays(365);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DeviceLayout.Preference preference = isSafe(request)
                ? DeviceLayout.Preference.fromParameter(queryParameter(request.getQueryString()))
                : null;
        if (preference != null) {
            writeCookie(request, response, preference);
        } else {
            preference = DeviceLayout.Preference.fromCookie(cookieValue(request));
        }
        boolean eink = preference.einkFor(request.getHeader(HttpHeaders.USER_AGENT));
        request.setAttribute(EINK_ATTRIBUTE, eink);
        request.setAttribute(PREFERENCE_ATTRIBUTE, preference.label());
        applyType(request, response);
        addVary(response);
        return true;
    }

    /**
     * The e-ink reading type: {@code ?text=s|m|l} and {@code ?font=sans|serif} on a GET are
     * stored in the {@code eink-type} cookie for a year, otherwise the cookie is read.
     * The page gets the extra {@code <html>} classes, and the size and face for Settings.
     */
    private static void applyType(HttpServletRequest request, HttpServletResponse response) {
        EinkType type = EinkType.fromCookie(cookieValue(request, TYPE_COOKIE));
        if (isSafe(request)) {
            String query = request.getQueryString();
            EinkType chosen = type.withSize(queryParameter(query, SIZE_PARAMETER))
                    .withFace(queryParameter(query, FACE_PARAMETER));
            if (!chosen.equals(type)) {
                type = chosen;
                writeCookie(request, response, TYPE_COOKIE, type.isDefault() ? null : type.cookieValue());
            }
        }
        request.setAttribute(TYPE_CLASSES_ATTRIBUTE, type.htmlClasses());
        request.setAttribute(TYPE_SIZE_ATTRIBUTE, type.size());
        request.setAttribute(TYPE_FACE_ATTRIBUTE, type.face());
    }

    /**
     * Only a GET or HEAD can change the layout. The parameter is read from the
     * query string itself: {@code getParameter} would parse the body of a form
     * POST or a webhook, which its handler then could not read.
     */
    private static boolean isSafe(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method);
    }

    static String queryParameter(String query) {
        return queryParameter(query, PARAMETER);
    }

    static String queryParameter(String query, String parameter) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (parameter.equals(name)) {
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void addVary(HttpServletResponse response) {
        for (String existing : response.getHeaders(HttpHeaders.VARY)) {
            if (existing.contains("User-Agent")) {
                return;
            }
        }
        response.addHeader(HttpHeaders.VARY, "User-Agent, Cookie");
    }

    private static void writeCookie(HttpServletRequest request, HttpServletResponse response,
                                    DeviceLayout.Preference preference) {
        writeCookie(request, response, COOKIE, preference.cookieValue());
    }

    /** A year-long, script-readable cookie; a null value deletes it. */
    private static void writeCookie(HttpServletRequest request, HttpServletResponse response,
                                    String name, String value) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .path("/")
                .maxAge(value == null ? Duration.ZERO : COOKIE_LIFETIME)
                .sameSite("Lax")
                .secure(request.isSecure())
                .httpOnly(false)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookieValue(HttpServletRequest request) {
        return cookieValue(request, COOKIE);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
