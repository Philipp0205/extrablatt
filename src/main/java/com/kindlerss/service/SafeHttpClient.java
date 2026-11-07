package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Downloads remote content with SSRF protections: http(s) only, no proxies, no
 * cookies, redirects followed by hand (each hop re-checked), and caps on size, on
 * each wait for data and on the whole fetch.
 *
 * <p>The address check happens where the connection is made: every address a DNS
 * lookup returns is checked by the resolver the connection itself uses, so a host
 * that answers with a public address for a pre-check and a private one a moment
 * later (DNS rebinding) is still refused. The request keeps the original host name,
 * so TLS and virtual hosting behave as usual.
 */
@Component
public class SafeHttpClient {

    /**
     * A fetch that did not work. {@link #getMessage()} is for the log and may name
     * hosts, addresses and low-level errors; {@link #userMessage()} is the sentence
     * a reader may see.
     */
    public static final class FetchException extends RuntimeException {
        static final String GENERIC = "That address could not be loaded";

        private final String userMessage;

        public FetchException(String message) {
            this(message, GENERIC, null);
        }

        public FetchException(String message, Throwable cause) {
            this(message, GENERIC, cause);
        }

        public FetchException(String message, String userMessage, Throwable cause) {
            super(message, cause);
            this.userMessage = userMessage == null || userMessage.isBlank() ? GENERIC : userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    public record FetchedContent(URI finalUri, String body, String contentType) {}

    /** A DNS answer that points somewhere a fetch must not go. */
    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException(String host, InetAddress address) {
            super("Resolved address is not allowed: " + host + " -> " + address.getHostAddress());
        }
    }

    /** Name lookup, swappable in tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT =
            "Extrablatt/1.0 (+https://github.com/Philipp0205/extrablatt; personal feed reader)";
    private static final String ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*;q=0.8";

    /** Hostnames rejected before DNS (SSRF). Not used as request targets. */
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "metadata.google.internal");

    /**
     * Address ranges no fetch may reach: this host, private and shared networks,
     * link-local (cloud metadata lives there), documentation and benchmarking
     * ranges, multicast and reserved space, plus the IPv6 transition prefixes that
     * can carry an IPv4 address inside them (mapped, compatible, NAT64, 6to4, Teredo).
     */
    private static final List<Cidr> BLOCKED_RANGES = List.of(
            Cidr.parse("0.0.0.0/8"),
            Cidr.parse("10.0.0.0/8"),
            Cidr.parse("100.64.0.0/10"),
            Cidr.parse("127.0.0.0/8"),
            Cidr.parse("169.254.0.0/16"),
            Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.0.0.0/24"),
            Cidr.parse("192.0.2.0/24"),
            Cidr.parse("192.88.99.0/24"),
            Cidr.parse("192.168.0.0/16"),
            Cidr.parse("198.18.0.0/15"),
            Cidr.parse("198.51.100.0/24"),
            Cidr.parse("203.0.113.0/24"),
            Cidr.parse("224.0.0.0/4"),
            Cidr.parse("240.0.0.0/4"),
            Cidr.parse("::/128"),
            Cidr.parse("::1/128"),
            Cidr.parse("::/96"),
            Cidr.parse("::ffff:0:0/96"),
            Cidr.parse("64:ff9b::/96"),
            Cidr.parse("64:ff9b:1::/48"),
            Cidr.parse("100::/64"),
            Cidr.parse("2001::/23"),
            Cidr.parse("2001:db8::/32"),
            Cidr.parse("2002::/16"),
            Cidr.parse("3fff::/20"),
            Cidr.parse("5f00::/16"),
            Cidr.parse("fc00::/7"),
            Cidr.parse("fe80::/10"),
            Cidr.parse("fec0::/10"),
            Cidr.parse("ff00::/8"));

    private final AppProperties properties;
    private final DnsResolver resolver;
    private final PoolingHttpClientConnectionManager connections;
    private final CloseableHttpClient httpClient;
    private final ScheduledThreadPoolExecutor deadlines;

    @Autowired
    public SafeHttpClient(AppProperties properties) {
        this(properties, InetAddress::getAllByName, SafeHttpClient::isBlockedAddress);
    }

    /** For tests: a stand-in DNS and a different idea of which addresses are off limits. */
    SafeHttpClient(AppProperties properties, Resolver upstream, Predicate<InetAddress> blocked) {
        this.properties = properties;
        this.resolver = new GuardedResolver(upstream, blocked);
        AppProperties.Http http = properties.http();
        this.connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(http.connectTimeout()))
                        .setSocketTimeout(Timeout.of(http.readTimeout()))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(Timeout.of(http.connectTimeout()))
                        .build())
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(4)
                .build();
        // Deliberately no useSystemProperties(): no proxy from the environment, and
        // so nothing between this client and the address the resolver approved.
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent(USER_AGENT)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setProtocolUpgradeEnabled(false)
                        .setResponseTimeout(Timeout.of(http.readTimeout()))
                        .build())
                .build();
        this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "safe-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
        httpClient.close(CloseMode.IMMEDIATE);
        connections.close(CloseMode.IMMEDIATE);
    }

    public FetchedContent get(String url) {
        long deadline = System.nanoTime() + properties.http().totalTimeout().toNanos();
        URI uri = validateSyntax(url);
        for (int hop = 0; ; hop++) {
            Hop result = fetchOnce(uri, deadline);
            if (result.content() != null) {
                return result.content();
            }
            if (hop >= MAX_REDIRECTS) {
                throw new FetchException("Too many redirects", "That address redirects too many times", null);
            }
            URI next;
            try {
                next = uri.resolve(result.location());
            } catch (IllegalArgumentException e) {
                throw new FetchException("Invalid redirect target from " + uri, "That address could not be loaded", e);
            }
            uri = validateSyntax(next.toString());
        }
    }

    /** Either the final content, or where a redirect points. */
    private record Hop(FetchedContent content, String location) {}

    private Hop fetchOnce(URI uri, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw timedOut(uri, null);
        }
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", ACCEPT);
        ScheduledFuture<?> watchdog = deadlines.schedule(request::cancel, remaining, TimeUnit.NANOSECONDS);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            if (status >= 300 && status < 400) {
                Header location = response.getFirstHeader("Location");
                if (location == null || location.getValue() == null || location.getValue().isBlank()) {
                    throw new FetchException("Redirect without Location header from " + uri);
                }
                return new Hop(null, location.getValue().trim());
            }
            if (status < 200 || status >= 300) {
                throw new FetchException("HTTP " + status + " for " + uri,
                        "The site answered with an error (HTTP " + status + ")", null);
            }
            HttpEntity entity = response.getEntity();
            Header typeHeader = response.getFirstHeader("Content-Type");
            String contentType = typeHeader == null || typeHeader.getValue() == null
                    ? "application/octet-stream" : typeHeader.getValue();
            int maxBytes = properties.http().maxBytes();
            if (entity != null && entity.getContentLength() > maxBytes) {
                throw tooLarge(maxBytes);
            }
            byte[] bytes = entity == null ? new byte[0] : readLimited(entity.getContent(), maxBytes, deadline, uri);
            return new Hop(new FetchedContent(uri, new String(bytes, charsetFromContentType(contentType)),
                    contentType), null);
        } catch (FetchException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            BlockedAddressException blocked = findCause(e, BlockedAddressException.class);
            if (blocked != null) {
                throw new FetchException(blocked.getMessage(), "That address is not allowed", e);
            }
            if (request.isCancelled() || System.nanoTime() - deadline >= 0
                    || findCause(e, SocketTimeoutException.class) != null) {
                throw timedOut(uri, e);
            }
            if (findCause(e, UnknownHostException.class) != null) {
                throw new FetchException("Host could not be resolved: " + uri.getHost(),
                        "That site could not be found", e);
            }
            throw new FetchException("Failed to fetch " + uri + ": " + e, e);
        } finally {
            watchdog.cancel(false);
        }
    }

    /**
     * Checks the address as written and that its host resolves only to allowed
     * addresses. A pre-check for callers that want to skip a bad candidate early;
     * {@link #get} does not rely on it, since the connection checks again.
     */
    public URI validateAndResolve(String url) {
        URI uri = validateSyntax(url);
        String host = uri.getHost();
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new FetchException("Host could not be resolved: " + host, "That site could not be found", null);
            }
        } catch (BlockedAddressException e) {
            throw new FetchException(e.getMessage(), "That address is not allowed", e);
        } catch (UnknownHostException e) {
            throw new FetchException("DNS resolution failed for " + host + ": " + e.getMessage(),
                    "That site could not be found", e);
        }
        return uri;
    }

    /** Scheme, host and the names refused outright; no DNS. */
    private static URI validateSyntax(String url) {
        if (url == null || url.isBlank()) {
            throw new FetchException("URL is required", "URL is required", null);
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid URL", "That is not a valid web address", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new FetchException("Only http and https URLs are allowed", "Only http and https URLs are allowed", null);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new FetchException("URL must include a host", "URL must include a host", null);
        }
        if (uri.getRawUserInfo() != null) {
            throw new FetchException("URL carries credentials", "That address is not allowed", null);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            throw new FetchException("Host is not allowed: " + host, "That address is not allowed", null);
        }
        return uri.normalize();
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        for (Cidr range : BLOCKED_RANGES) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves with the upstream DNS and refuses the whole answer if any address is blocked. */
    private record GuardedResolver(Resolver upstream, Predicate<InetAddress> blocked) implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = upstream.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            for (InetAddress address : addresses) {
                if (blocked.test(address)) {
                    throw new BlockedAddressException(host, address);
                }
            }
            return addresses;
        }

        @Override
        public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
            List<InetSocketAddress> result = new ArrayList<>();
            for (InetAddress address : resolve(host)) {
                result.add(new InetSocketAddress(address, port));
            }
            return result;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            // Only used for Kerberos style auth, which this client never does.
            return host;
        }
    }

    /** An address prefix, compared byte by byte. */
    private record Cidr(byte[] prefix, int bits) {
        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            try {
                // Literals only, so this never touches DNS.
                byte[] prefix = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
                int bits = Integer.parseInt(cidr.substring(slash + 1));
                if (cidr.contains(":") && prefix.length == 4) {
                    // Java folds "::ffff:a.b.c.d" literals into IPv4; keep the IPv6 form.
                    prefix = mapped(prefix);
                }
                return new Cidr(prefix, bits);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(cidr, e);
            }
        }

        private static byte[] mapped(byte[] v4) {
            byte[] v6 = new byte[16];
            v6[10] = (byte) 0xff;
            v6[11] = (byte) 0xff;
            System.arraycopy(v4, 0, v6, 12, 4);
            return v6;
        }

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (address[full] & mask) == (prefix[full] & mask);
        }
    }

    private static FetchException timedOut(URI uri, Throwable cause) {
        return new FetchException("Fetch of " + uri + " timed out", "The site took too long to answer", cause);
    }

    private static FetchException tooLarge(int maxBytes) {
        return new FetchException("Response exceeds maximum allowed size (" + maxBytes + " bytes)",
                "That page is too large to load", null);
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static byte[] readLimited(InputStream in, int maxBytes, long deadline, URI uri) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (System.nanoTime() - deadline >= 0) {
                    throw timedOut(uri, null);
                }
                total += read;
                if (total > maxBytes) {
                    throw tooLarge(maxBytes);
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
