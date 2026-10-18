package com.kindlerss.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kindlerss.service.SafeHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Talks to the Nextcloud News app over its REST API v1.3, using an app password. */
public class NextcloudNewsProvider implements SyncProvider {

    static final String API_PATH = "/index.php/apps/news/api/v1-3";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 40;
    private static final int READ_BATCH = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiBase;
    private final String authorization;
    private final HttpClient http;
    private final Duration timeout;
    private final boolean allowPrivateHosts;
    private final SafeHttpClient safeHttp;

    public NextcloudNewsProvider(String baseUrl, String username, String password,
                                 SafeHttpClient safeHttp, Duration connectTimeout, Duration readTimeout,
                                 boolean allowPrivateHosts) {
        this.apiBase = normalizeBase(baseUrl) + API_PATH;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.safeHttp = safeHttp;
        this.allowPrivateHosts = allowPrivateHosts;
        this.timeout = readTimeout;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** Strips trailing slashes and a pasted-in {@code /index.php/...} suffix. */
    public static String normalizeBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        int index = base.indexOf("/index.php");
        if (index >= 0) {
            base = base.substring(0, index);
        }
        return base.replaceAll("/+$", "");
    }

    @Override
    public void testConnection() {
        call("GET", "/version", null);
    }

    @Override
    public List<RemoteFeed> fetchFeeds() {
        Map<Long, String> folders = folderNames();
        List<RemoteFeed> result = new ArrayList<>();
        for (JsonNode feed : call("GET", "/feeds", null).path("feeds")) {
            result.add(toFeed(feed, folders));
        }
        return result;
    }

    @Override
    public RemoteFeed addFeed(String url, String category) {
        Long folderId = folderIdFor(category, true);
        ObjectNode body = JSON.createObjectNode().put("url", url);
        if (folderId == null) {
            body.putNull("folderId");
        } else {
            body.put("folderId", folderId);
        }
        Response response = send("POST", "/feeds", body);
        if (response.status == 409) {
            for (RemoteFeed existing : fetchFeeds()) {
                if (existing.url().equals(url)) {
                    return existing;
                }
            }
            throw new SyncException("Feed already exists remotely but could not be found: " + url);
        }
        response.requireOk("add feed " + url);
        JsonNode created = response.body.path("feeds").path(0);
        if (created.isMissingNode()) {
            throw new SyncException("Provider returned no feed for " + url);
        }
        return toFeed(created, folderNames());
    }

    @Override
    public void removeFeed(long remoteFeedId) {
        Response response = send("DELETE", "/feeds/" + remoteFeedId, null);
        if (response.status != 404) {
            response.requireOk("remove feed " + remoteFeedId);
        }
    }

    @Override
    public void moveFeed(long remoteFeedId, String category) {
        Long folderId = folderIdFor(category, true);
        ObjectNode body = JSON.createObjectNode();
        if (folderId == null) {
            body.putNull("folderId");
        } else {
            body.put("folderId", folderId);
        }
        Response response = send("POST", "/feeds/" + remoteFeedId + "/move", body);
        if (response.status != 404) {
            response.requireOk("move feed " + remoteFeedId);
        }
    }

    @Override
    public ItemPage fetchChangedItems(String cursor) {
        List<RemoteItem> items = new ArrayList<>();
        if (cursor == null || cursor.isBlank()) {
            long offset = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                JsonNode body = call("GET", "/items?type=3&getRead=true&oldestFirst=false&batchSize="
                        + PAGE_SIZE + "&offset=" + offset, null);
                List<RemoteItem> batch = toItems(body);
                items.addAll(batch);
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
                offset = batch.stream().mapToLong(RemoteItem::id).min().orElse(0);
            }
        } else {
            long since = parseCursor(cursor);
            items.addAll(toItems(call("GET", "/items/updated?type=3&id=0&lastModified=" + since, null)));
        }
        long newest = items.stream().mapToLong(RemoteItem::lastModified).max().orElse(-1);
        String next = newest >= 0 ? Long.toString(newest) : cursor;
        return new ItemPage(items, next);
    }

    @Override
    public List<RemoteItem> fetchFeedItems(long remoteFeedId, int limit) {
        int size = Math.min(Math.max(limit, 1), PAGE_SIZE);
        return toItems(call("GET", "/items?type=0&id=" + remoteFeedId + "&getRead=true&oldestFirst=false&batchSize="
                + size + "&offset=0", null));
    }

    @Override
    public void setRead(Collection<Long> remoteItemIds, boolean read) {
        List<Long> ids = new ArrayList<>(remoteItemIds);
        String path = read ? "/items/read/multiple" : "/items/unread/multiple";
        for (int from = 0; from < ids.size(); from += READ_BATCH) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode array = body.putArray("itemIds");
            ids.subList(from, Math.min(from + READ_BATCH, ids.size())).forEach(array::add);
            call("POST", path, body);
        }
    }

    private Map<Long, String> folderNames() {
        Map<Long, String> names = new HashMap<>();
        for (JsonNode folder : call("GET", "/folders", null).path("folders")) {
            names.put(folder.path("id").asLong(), folder.path("name").asText(""));
        }
        return names;
    }

    private Long folderIdFor(String category, boolean create) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String wanted = category.trim();
        for (Map.Entry<Long, String> folder : folderNames().entrySet()) {
            if (folder.getValue().trim().equalsIgnoreCase(wanted)) {
                return folder.getKey();
            }
        }
        if (!create) {
            return null;
        }
        JsonNode created = call("POST", "/folders", JSON.createObjectNode().put("name", wanted))
                .path("folders").path(0);
        if (created.isMissingNode()) {
            throw new SyncException("Provider did not create folder " + wanted);
        }
        return created.path("id").asLong();
    }

    private static RemoteFeed toFeed(JsonNode feed, Map<Long, String> folders) {
        long folderId = feed.path("folderId").asLong(0);
        String category = folderId == 0 ? null : folders.get(folderId);
        return new RemoteFeed(feed.path("id").asLong(), feed.path("url").asText(""),
                feed.path("title").asText(""), category == null || category.isBlank() ? null : category);
    }

    private static List<RemoteItem> toItems(JsonNode body) {
        List<RemoteItem> items = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            items.add(new RemoteItem(
                    item.path("id").asLong(),
                    item.path("feedId").asLong(),
                    item.path("guid").asText(null),
                    item.path("url").asText(null),
                    !item.path("unread").asBoolean(true),
                    item.path("lastModified").asLong(0)));
        }
        return items;
    }

    private static long parseCursor(String cursor) {
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private JsonNode call(String method, String path, JsonNode body) {
        Response response = send(method, path, body);
        response.requireOk(method + " " + path);
        return response.body;
    }

    private Response send(String method, String path, JsonNode body) {
        URI uri;
        try {
            uri = safeHttp.validateAndResolve(apiBase + path, allowPrivateHosts);
        } catch (SafeHttpClient.FetchException e) {
            throw new SyncException("Server address not allowed: " + e.getMessage(), e);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = JSON.createObjectNode();
            String text = response.body();
            if (text != null && !text.isBlank() && response.statusCode() < 300) {
                try {
                    parsed = JSON.readTree(text);
                } catch (IOException e) {
                    throw new SyncException("Provider did not answer with JSON — is the Nextcloud News app installed?", e);
                }
            }
            return new Response(response.statusCode(), parsed);
        } catch (IOException e) {
            throw new SyncException("Could not reach the server: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncException("Sync interrupted", e);
        }
    }

    private record Response(int status, JsonNode body) {
        void requireOk(String what) {
            if (status == 401 || status == 403) {
                throw new SyncException("Nextcloud rejected the username or app password");
            }
            if (status >= 300 && status < 400) {
                throw new SyncException("Server redirected (HTTP " + status
                        + ") — check the address, including https://");
            }
            if (status >= 400) {
                throw new SyncException("Nextcloud News returned HTTP " + status + " for " + what);
            }
        }
    }
}
