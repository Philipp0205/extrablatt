package com.kindlerss.sync;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.SyncRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static FeedRepository feeds;
    private static ArticleRepository articles;
    private static SyncRepository syncRepository;
    private static SyncService service;
    private static int userCounter;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        var dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        feeds = new FeedRepository(jdbc);
        articles = new ArticleRepository(jdbc);
        syncRepository = new SyncRepository(jdbc);
        EntitlementService entitlements = mock(EntitlementService.class);
        Entitlement entitlement = mock(Entitlement.class);
        when(entitlement.maxFeeds()).thenReturn(50);
        when(entitlements.forUser(anyLong())).thenReturn(entitlement);
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null, null,
                null, null, null, null, new AppProperties.Sync("key", false));
        service = new SyncService(syncRepository, feeds, mock(FeedService.class), entitlements,
                new SecretCipher(properties), new SafeHttpClient(properties), properties);
    }

    @AfterAll
    static void stop() throws Exception {
        postgres.close();
    }

    private long newUser() {
        return new UserRepository(jdbc).insert("sync" + (userCounter++) + "@example.com", "hash").id();
    }

    private long article(long feedId, String guid) {
        return articles.insert(feedId, guid, guid, "https://x/" + guid, null, null, null, null);
    }

    private boolean isRead(long articleId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT read FROM articles WHERE id = ?", Boolean.class, articleId));
    }

    @Test
    void firstSyncMergesFeedsAndReadState() {
        long user = newUser();
        var local = feeds.insert(user, "A", "https://a.example/feed", null, null);
        long readLocally = article(local.id(), "g-local-read");
        long readRemotely = article(local.id(), "g-remote-read");
        long untouched = article(local.id(), "g-untouched");
        articles.markRead(user, readLocally, true);

        FakeProvider provider = new FakeProvider();
        provider.remoteFeeds.add(new RemoteFeed(10, "https://a.example/feed", "A", "Tech"));
        provider.remoteFeeds.add(new RemoteFeed(11, "https://b.example/feed", "B", null));
        provider.items.add(new RemoteItem(100, 10, "g-local-read", null, false, 1));
        provider.items.add(new RemoteItem(101, 10, "g-remote-read", null, true, 2));
        provider.items.add(new RemoteItem(102, 10, "g-untouched", null, false, 3));

        service.syncFeeds(user, provider, true);
        service.syncItems(user, provider, null, true);

        assertEquals(2, feeds.findAll(user).size());
        assertEquals("Tech", feeds.findById(user, local.id()).orElseThrow().category());
        assertTrue(isRead(readLocally), "local read survives the merge");
        assertTrue(isRead(readRemotely), "remote read is applied");
        assertFalse(isRead(untouched));
        assertEquals(List.of(100L), provider.readPushed, "local read state is pushed up");
    }

    @Test
    void laterSyncLetsRemoteWinAndPushesLocalChanges() {
        long user = newUser();
        var feed = feeds.insert(user, "A", "https://a.example/feed", null, null);
        syncRepository.setFeedRemote(feed.id(), 10);
        long a = article(feed.id(), "a");
        long b = article(feed.id(), "b");
        syncRepository.linkArticle(a, 100);
        syncRepository.linkArticle(b, 101);
        articles.markRead(user, a, true);   // local change, not yet pushed
        articles.markRead(user, b, true);   // also changed locally...

        FakeProvider provider = new FakeProvider();
        provider.remoteFeeds.add(new RemoteFeed(10, "https://a.example/feed", "A", null));
        provider.items.add(new RemoteItem(101, 10, "b", null, false, 9)); // ...but remote says unread

        service.syncFeeds(user, provider, false);
        service.syncItems(user, provider, "5", false);

        assertFalse(isRead(b), "remote wins the conflict");
        assertTrue(isRead(a));
        assertEquals(List.of(100L), provider.readPushed);
        assertTrue(syncRepository.findDirtyArticles(user).isEmpty());
    }

    @Test
    void deletedFeedIsPushedAndNotRecreated() {
        long user = newUser();
        var feed = feeds.insert(user, "A", "https://a.example/feed", null, null);
        syncRepository.setFeedRemote(feed.id(), 10);
        feeds.deleteById(user, feed.id());

        FakeProvider provider = new FakeProvider();
        provider.remoteFeeds.add(new RemoteFeed(10, "https://a.example/feed", "A", null));
        service.syncFeeds(user, provider, false);

        assertEquals(List.of(10L), provider.removed);
        assertTrue(feeds.findAll(user).isEmpty());
        assertTrue(syncRepository.findTombstones(user).isEmpty());
    }

    @Test
    void feedRemovedRemotelyDisappearsLocally() {
        long user = newUser();
        var feed = feeds.insert(user, "A", "https://a.example/feed", null, null);
        syncRepository.setFeedRemote(feed.id(), 10);

        service.syncFeeds(user, new FakeProvider(), false);

        assertTrue(feeds.findAll(user).isEmpty());
    }

    @Test
    void localCategoryChangeIsPushed() {
        long user = newUser();
        var feed = feeds.insert(user, "A", "https://a.example/feed", null, "Old");
        syncRepository.setFeedRemote(feed.id(), 10);
        feeds.updateCategory(user, feed.id(), "Mine");

        FakeProvider provider = new FakeProvider();
        provider.remoteFeeds.add(new RemoteFeed(10, "https://a.example/feed", "A", "Old"));
        service.syncFeeds(user, provider, false);

        assertEquals(Map.of(10L, "Mine"), provider.moved);
        assertEquals("Mine", feeds.findById(user, feed.id()).orElseThrow().category());
    }

    static class FakeProvider implements SyncProvider {
        final List<RemoteFeed> remoteFeeds = new ArrayList<>();
        final List<RemoteItem> items = new ArrayList<>();
        final List<Long> readPushed = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final Map<Long, String> moved = new java.util.HashMap<>();
        private long nextId = 500;

        public void testConnection() {}

        public List<RemoteFeed> fetchFeeds() {
            return new ArrayList<>(remoteFeeds);
        }

        public RemoteFeed addFeed(String url, String category) {
            RemoteFeed feed = new RemoteFeed(nextId++, url, url, category);
            remoteFeeds.add(feed);
            return feed;
        }

        public void removeFeed(long id) {
            removed.add(id);
        }

        public void moveFeed(long id, String category) {
            moved.put(id, category);
        }

        public ItemPage fetchChangedItems(String cursor) {
            return new ItemPage(items, "9999");
        }

        public List<RemoteItem> fetchFeedItems(long id, int limit) {
            return List.of();
        }

        public void setRead(Collection<Long> ids, boolean read) {
            if (read) {
                readPushed.addAll(ids);
            }
        }
    }
}
