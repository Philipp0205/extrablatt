package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads remote content with SSRF protections: http(s) only, DNS resolution checks,
 * private/loopback/link-local/multicast rejection, size and timeout caps.
 */
@Component
public class SafeHttpClient {

    public static final class FetchException extends RuntimeException {
        private final int status;

        public FetchException(String message) {
            this(message, 0);
        }

        public FetchException(String message, int status) {
            super(message);
            this.status = status;
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
            this.status = 0;
        }

        /** The HTTP status that was refused, or 0 when the request never got a response. */
        public int status() {
            return status;
        }
    }

    /**
     * A fetched document. {@code truncated} says the response was longer than the
     * size cap and only its beginning is here.
     */
    public record FetchedContent(URI finalUri, String body, String contentType, boolean truncated) {
        public FetchedContent(URI finalUri, String body, String contentType) {
            this(finalUri, body, contentType, false);
        }

        /** Whether the response looks like markup Readability or jsoup can work with. */
        public boolean isMarkup() {
            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            return type.isBlank()
                    || type.startsWith("application/octet-stream")
                    || type.contains("html")
                    || type.contains("xml")
                    || type.startsWith("text/plain");
        }
    }

    /** Hostnames rejected before DNS (SSRF). Not used as request targets. */
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "metadata.google.internal");

    /** Identifies the reader honestly; preferred for every request. */
    private static final String READER_USER_AGENT =
            "KindleRSS/1.0 (+https://github.com/Philipp0205/kindle-rss; personal feed reader)";

    /**
     * Sites that serve articles to browsers only. Used for a second attempt after
     * a refusal that a plain reader user agent is the likeliest cause of.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    /** Statuses worth one retry as a browser rather than as a feed reader. */
    private static final Set<Integer> RETRY_AS_BROWSER = Set.of(401, 403, 406, 429, 451);

    private final HttpClient httpClient;
    private final AppProperties properties;

    public SafeHttpClient(AppProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.http().connectTimeout())
                .build();
    }

    /** Fetches a document whole; a response past the size cap is an error. */
    public FetchedContent get(String url) {
        return fetch(url, false);
    }

    /**
     * Fetches a page to read from, keeping the beginning of a response that runs
     * past the size cap: half of a page still holds the article, where nothing at
     * all leaves a reader with the few lines the feed happened to carry.
     */
    public FetchedContent getPage(String url) {
        return fetch(url, true);
    }

    private FetchedContent fetch(String url, boolean allowTruncation) {
        try {
            return get(url, 0, allowTruncation, READER_USER_AGENT);
        } catch (FetchException e) {
            if (!RETRY_AS_BROWSER.contains(e.status())) {
                throw e;
            }
            return get(url, 0, allowTruncation, BROWSER_USER_AGENT);
        }
    }

    private FetchedContent get(String url, int redirectCount, boolean allowTruncation, String userAgent) {
        if (redirectCount > 5) {
            throw new FetchException("Too many redirects");
        }
        URI uri = validateAndResolve(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.http().readTimeout())
                .header("User-Agent", userAgent)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new FetchException("Redirect without Location header");
                }
                URI next = uri.resolve(location);
                return get(next.toString(), redirectCount + 1, allowTruncation, userAgent);
            }
            if (status < 200 || status >= 300) {
                throw new FetchException("HTTP " + status + " for " + uri, status);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            int maxBytes = properties.http().maxBytes();
            byte[] bytes = readLimited(response.body(), maxBytes, allowTruncation);
            Charset charset = charsetFromContentType(contentType);
            return new FetchedContent(uri, new String(bytes, charset), contentType, bytes.length >= maxBytes);
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            throw new FetchException("Failed to fetch " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Fetch interrupted", e);
        }
    }

    public URI validateAndResolve(String url) {
        if (url == null || url.isBlank()) {
            throw new FetchException("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new FetchException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new FetchException("URL must include a host");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new FetchException("Host is not allowed");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new FetchException("Host could not be resolved");
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new FetchException("Resolved address is not allowed: " + address.getHostAddress());
                }
            }
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException("DNS resolution failed for " + host + ": " + e.getMessage(), e);
        }
        return uri.normalize();
    }

    static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocal(address)
                || isCarrierGradeNat(address);
    }

    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        // fc00::/7
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        // 100.64.0.0/10
        return bytes.length == 4
                && (bytes[0] & 0xff) == 100
                && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
    }

    private static byte[] readLimited(InputStream in, int maxBytes, boolean allowTruncation) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    if (!allowTruncation) {
                        throw new FetchException("Response exceeds maximum allowed size (" + maxBytes + " bytes)");
                    }
                    out.write(buffer, 0, read - (total - maxBytes));
                    return out.toByteArray();
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = trimmed.substring("charset=".length()).trim().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
