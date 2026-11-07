package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpClientTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private HttpServer server;
    private final List<String> hostHeaders = new CopyOnWriteArrayList<>();
    private final List<SafeHttpClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        clients.forEach(SafeHttpClient::close);
    }

    @Test
    void blocksPrivateAndLoopbackAddresses() throws Exception {
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("172.16.5.5")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("169.254.1.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("100.64.1.2")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("224.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("fc00::1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.1.2.3", "169.254.169.254", "192.0.0.8", "192.0.2.1", "192.88.99.1",
            "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "240.0.0.1", "255.255.255.255",
            "::", "::127.0.0.1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1", "100::1", "2001::1", "2001:db8::1",
            "2002:a9fe:a9fe::1", "3fff::1", "5f00::1", "fd12:3456::1", "fe80::1", "fec0::1", "ff02::1"
    })
    void blocksReservedAndTransitionRanges(String literal) throws Exception {
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName(literal)), literal);
    }

    @Test
    void blocksIpv4MappedIpv6() throws Exception {
        // Java turns "::ffff:a.b.c.d" literals into IPv4, so build the 16 bytes by hand.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 169;
        mapped[13] = (byte) 254;
        mapped[14] = (byte) 169;
        mapped[15] = (byte) 254;
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByAddress(mapped)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.215.14", "2606:4700:4700::1111", "2a00:1450:4001::200e"})
    void allowsPublicAddresses(String literal) throws Exception {
        assertFalse(SafeHttpClient.isBlockedAddress(InetAddress.getByName(literal)), literal);
    }

    @Test
    void rejectsNonHttpSchemesWithoutDns() {
        AppProperties props = new AppProperties(
                "from@example.com", null, "remember",
                new AppProperties.Http(null, null, 1024), null, null, null, null,
                null, null
        );
        SafeHttpClient client = track(new SafeHttpClient(props));
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("file:///etc/passwd"));
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("ftp://example.com/a"));
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("javascript:alert(1)"));
    }

    @Test
    void rejectsBlockedNamesAndCredentialsBeforeDns() {
        AtomicInteger lookups = new AtomicInteger();
        SafeHttpClient client = client(host -> {
            lookups.incrementAndGet();
            return new InetAddress[] {LOOPBACK};
        }, 1024, Duration.ofSeconds(5));
        for (String url : List.of("http://localhost/", "http://LOCALHOST./", "http://box.local/",
                "http://metadata.google.internal/", "http://x.internal/", "http://user:pw@feeds.test/")) {
            SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class, () -> client.get(url));
            assertEquals("That address is not allowed", e.userMessage(), url);
        }
        assertEquals(0, lookups.get());
    }

    @Test
    void connectsToTheCheckedAddressAndKeepsTheHostName() throws Exception {
        startServer();
        server.createContext("/feed", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "application/rss+xml; charset=utf-8", "<rss/>");
        });
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchedContent content = client.get("http://feeds.test:" + port() + "/feed");

        assertEquals("<rss/>", content.body());
        assertEquals("application/rss+xml; charset=utf-8", content.contentType());
        assertEquals(List.of("feeds.test:" + port()), hostHeaders);
    }

    @Test
    void dnsRebindingIsCaughtAtConnectTime() throws Exception {
        startServer();
        server.createContext("/", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "text/plain", "secret");
        });
        // First answer is fine (the pre-check), the next one points at cloud metadata.
        AtomicInteger calls = new AtomicInteger();
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        SafeHttpClient.Resolver rebinding = host -> calls.getAndIncrement() == 0
                ? new InetAddress[] {LOOPBACK}
                : new InetAddress[] {metadata};
        SafeHttpClient client = client(rebinding, 1024, Duration.ofSeconds(5));
        String url = "http://rebind.test:" + port() + "/";

        client.validateAndResolve(url);
        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class, () -> client.get(url));

        assertEquals("That address is not allowed", e.userMessage());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void anIpLiteralGoesThroughTheSameCheck() throws Exception {
        startServer();
        server.createContext("/", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "text/plain", "local");
        });
        AppProperties props = new AppProperties(
                "from@example.com", null, "remember",
                new AppProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(2), 1024, Duration.ofSeconds(5)),
                null, null, null, null, null, null);
        SafeHttpClient client = track(new SafeHttpClient(props));

        for (String url : List.of("http://127.0.0.1:" + port() + "/", "http://[::1]:" + port() + "/",
                "http://2130706433:" + port() + "/")) {
            SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class, () -> client.get(url));
            assertTrue(List.of("That address is not allowed", "That site could not be found").contains(e.userMessage()),
                    url + " -> " + e.userMessage());
        }
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void refusesAnAnswerThatMixesAllowedAndBlockedAddresses() throws Exception {
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK, metadata}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.validateAndResolve("http://mixed.test/"));
        assertEquals("That address is not allowed", e.userMessage());
    }

    @Test
    void redirectToABlockedHostIsRefused() throws Exception {
        startServer();
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://internal.test:" + port() + "/admin");
            respond(exchange, 302, "text/plain", "");
        });
        server.createContext("/admin", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "text/plain", "admin");
        });
        InetAddress internal = InetAddress.getByName("10.0.0.5");
        SafeHttpClient client = client(host -> host.startsWith("internal")
                ? new InetAddress[] {internal}
                : new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.get("http://public.test:" + port() + "/start"));

        assertEquals("That address is not allowed", e.userMessage());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void followsRedirectsAndReportsTheFinalAddress() throws Exception {
        startServer();
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new");
            respond(exchange, 301, "text/plain", "");
        });
        server.createContext("/new", exchange -> respond(exchange, 200, "text/html", "<p>moved</p>"));
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchedContent content = client.get("http://feeds.test:" + port() + "/old");

        assertEquals("<p>moved</p>", content.body());
        assertEquals("http://feeds.test:" + port() + "/new", content.finalUri().toString());
    }

    @Test
    void stopsARedirectLoop() throws Exception {
        startServer();
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            respond(exchange, 302, "text/plain", "");
        });
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.get("http://feeds.test:" + port() + "/loop"));
        assertEquals("That address redirects too many times", e.userMessage());
    }

    @Test
    void aServerThatTricklesBytesHitsTheTotalTimeout() throws Exception {
        startServer();
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (Exception ignored) {
                // client went away
            }
        });
        // Each byte arrives well within the read timeout; only the total cap can stop this.
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofMillis(800));

        long started = System.nanoTime();
        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.get("http://slow.test:" + port() + "/slow"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals("The site took too long to answer", e.userMessage());
        assertTrue(elapsedMillis < 5_000, "took " + elapsedMillis + " ms");
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        startServer();
        server.createContext("/big", exchange -> respond(exchange, 200, "text/plain", "x".repeat(4096)));
        server.createContext("/chunked", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("y".repeat(4096).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // client went away
            }
        });
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        for (String path : List.of("/big", "/chunked")) {
            SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                    () -> client.get("http://big.test:" + port() + path));
            assertEquals("That page is too large to load", e.userMessage(), path);
        }
    }

    @Test
    void anErrorStatusIsReported() throws Exception {
        startServer();
        server.createContext("/gone", exchange -> respond(exchange, 410, "text/plain", "gone"));
        SafeHttpClient client = client(host -> new InetAddress[] {LOOPBACK}, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.get("http://feeds.test:" + port() + "/gone"));
        assertEquals("The site answered with an error (HTTP 410)", e.userMessage());
    }

    @Test
    void anUnknownHostIsReported() {
        SafeHttpClient client = client(host -> {
            throw new UnknownHostException(host);
        }, 1024, Duration.ofSeconds(5));

        SafeHttpClient.FetchException e = assertThrows(SafeHttpClient.FetchException.class,
                () -> client.get("http://nowhere.test/"));
        assertEquals("That site could not be found", e.userMessage());
    }

    private SafeHttpClient client(SafeHttpClient.Resolver dns, int maxBytes, Duration totalTimeout) {
        AppProperties props = new AppProperties(
                "from@example.com", null, "remember",
                new AppProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(2), maxBytes, totalTimeout),
                null, null, null, null, null, null);
        // Loopback stands in for "the public internet" here; everything else keeps the real rules.
        return track(new SafeHttpClient(props, dns,
                address -> !address.isLoopbackAddress() && SafeHttpClient.isBlockedAddress(address)));
    }

    private SafeHttpClient track(SafeHttpClient client) {
        clients.add(client);
        return client;
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.start();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }
}
