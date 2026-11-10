package com.kindlerss.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.HandlerInterceptor;

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
 *   <li>{@code ?layout=kindle} or {@code ?layout=eink}, {@code ?layout=scroll}:
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
 * <p>The cookie is not HttpOnly on purpose: {@code app.js} reads it so that a reader
 * who chose scrolling is not switched to pages by the browser-side fallback.
 */
public class LayoutInterceptor implements HandlerInterceptor {

    public static final String COOKIE = "layout";
    public static final String PARAMETER = "layout";
    public static final String EINK_ATTRIBUTE = "einkLayout";
    public static final String PREFERENCE_ATTRIBUTE = "layoutPreference";
    static final Duration COOKIE_LIFETIME = Duration.ofDays(365);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DeviceLayout.Preference preference = DeviceLayout.Preference.fromParameter(request.getParameter(PARAMETER));
        if (preference != null) {
            writeCookie(request, response, preference);
        } else {
            preference = DeviceLayout.Preference.fromCookie(cookieValue(request));
        }
        boolean eink = preference.einkFor(request.getHeader(HttpHeaders.USER_AGENT));
        request.setAttribute(EINK_ATTRIBUTE, eink);
        request.setAttribute(PREFERENCE_ATTRIBUTE, preference.label());
        addVary(response);
        return true;
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
        String value = preference.cookieValue();
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value == null ? "" : value)
                .path("/")
                .maxAge(value == null ? Duration.ZERO : COOKIE_LIFETIME)
                .sameSite("Lax")
                .secure(request.isSecure())
                .httpOnly(false)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
