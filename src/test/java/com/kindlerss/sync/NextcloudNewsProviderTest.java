package com.kindlerss.sync;

import com.kindlerss.config.AppProperties;
import com.kindlerss.service.SafeHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NextcloudNewsProviderTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            requests.add(exchange.getRequestMethod() + " " + path.replace(NextcloudNewsProvider.API_PATH, ""));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = switch (path.replace(NextcloudNewsProvider.API_PATH, "").split("\\?")[0]) {
                case "/folders" -> exchange.getRequestMethod().equals("POST")
                        ? "{\"folders\":[{\"id\":7,\"name\":\"News\"}]}"
                        : "{\"folders\":[{\"id\":3,\"name\":\"Tech\"}]}";
                case "/feeds" -> "{\"feeds\":[{\"id\":10,\"url\":\"https://a.example/feed\",\"title\":\"A\",\"folderId\":3},"
                        + "{\"id\":11,\"url\":\"https://b.example/feed\",\"title\":\"B\",\"folderId\":0}]}";
                case "/items/updated" -> "{\"items\":[{\"id\":100,\"feedId\":10,\"guid\":\"g1\",\"url\":\"u1\","
                        + "\"unread\":false,\"lastModified\":5000}]}";
                default -> "{}";
            };
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NextcloudNewsProvider provider(boolean allowPrivate) {
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null, null,
                null, null, null, null, new AppProperties.Sync("k", allowPrivate));
        return new NextcloudNewsProvider("http://localhost:" + server.getAddress().getPort() + "/",
                "user", "pw", new SafeHttpClient(properties), Duration.ofSeconds(2), Duration.ofSeconds(2),
                allowPrivate);
    }

    @Test
    void mapsFeedsToCategoryNames() {
        List<RemoteFeed> feeds = provider(true).fetchFeeds();
        assertEquals("Tech", feeds.get(0).category());
        assertEquals(null, feeds.get(1).category());
    }

    @Test
    void incrementalPullReturnsItemsAndAdvancesCursor() {
        var page = provider(true).fetchChangedItems("4000");
        assertTrue(requests.get(0).contains("/items/updated?type=3&id=0&lastModified=4000"));
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).read());
        assertEquals("5000", page.nextCursor());
    }

    @Test
    void addFeedCreatesMissingFolder() {
        provider(true).addFeed("https://c.example/feed", "News");
        assertTrue(requests.contains("POST /folders"));
        assertTrue(bodies.stream().anyMatch(b -> b.contains("\"folderId\":7")));
    }

    @Test
    void pushesReadStateInBatches() {
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 250; i++) {
            ids.add(i);
        }
        provider(true).setRead(ids, true);
        assertEquals(2, requests.stream().filter(r -> r.equals("POST /items/read/multiple")).count());
    }

    @Test
    void rejectsBadCredentials() {
        status = 401;
        var e = assertThrows(SyncProvider.SyncException.class, () -> provider(true).testConnection());
        assertTrue(e.getMessage().contains("app password"));
    }

    @Test
    void blocksPrivateHostsUnlessAllowed() {
        var e = assertThrows(SyncProvider.SyncException.class, () -> provider(false).testConnection());
        assertTrue(e.getMessage().contains("not allowed"));
        assertFalse(requests.size() > 0);
    }

    @Test
    void normalizesBaseUrl() {
        assertEquals("https://cloud.example.com", NextcloudNewsProvider.normalizeBase(
                " https://cloud.example.com/index.php/apps/news/ "));
        assertEquals("https://cloud.example.com/nc", NextcloudNewsProvider.normalizeBase("https://cloud.example.com/nc//"));
    }
}
