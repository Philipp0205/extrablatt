package com.kindlerss.sync;

import com.kindlerss.repository.SyncRepository.SyncFeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedReconcilerTest {

    private static SyncFeed local(long id, String url, String category, Long remoteId, boolean dirty) {
        return new SyncFeed(id, url, "t", category, remoteId, dirty);
    }

    private static RemoteFeed remote(long id, String url, String category) {
        return new RemoteFeed(id, url, "t", category);
    }

    @Test
    void firstSyncMergesBothSides() {
        var plan = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", null, null, false),
                        local(2, "https://only-local.example/rss", "News", null, false)),
                List.of(remote(10, "http://a.example/feed/", "Tech"),
                        remote(11, "https://only-remote.example/rss", null)),
                Set.of(), true);

        assertEquals(1, plan.links.size());
        assertEquals(10, plan.links.get(0).remoteId());
        assertEquals("Tech", plan.localCategories.get(0).category());
        assertEquals(List.of(2L), plan.createRemote.stream().map(SyncFeed::id).toList());
        assertEquals(List.of(11L), plan.createLocal.stream().map(RemoteFeed::id).toList());
        assertTrue(plan.deleteLocal.isEmpty());
    }

    @Test
    void firstSyncKeepsLocalCategoryWhenRemoteIsAtRoot() {
        var plan = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", "News", null, false)),
                List.of(remote(10, "https://a.example/feed", null)), Set.of(), true);
        assertEquals(1, plan.moveRemote.size());
        assertTrue(plan.localCategories.isEmpty());
    }

    @Test
    void feedRemovedRemotelyIsRemovedLocally() {
        var plan = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", null, 10L, false)),
                List.of(), Set.of(), false);
        assertEquals(List.of(1L), plan.deleteLocal);
        assertTrue(plan.createRemote.isEmpty());
    }

    @Test
    void feedDeletedLocallyIsDeletedRemotelyNotRecreated() {
        var plan = FeedReconciler.reconcile(List.of(),
                List.of(remote(10, "https://a.example/feed", null)), Set.of(10L), false);
        assertEquals(List.of(10L), plan.deleteRemote);
        assertTrue(plan.createLocal.isEmpty());
    }

    @Test
    void remoteCategoryWinsUnlessChangedLocallySinceLastSync() {
        var remoteWins = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", "Old", 10L, false)),
                List.of(remote(10, "https://a.example/feed", "New")), Set.of(), false);
        assertEquals("New", remoteWins.localCategories.get(0).category());

        var localPushed = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", "Mine", 10L, true)),
                List.of(remote(10, "https://a.example/feed", "New")), Set.of(), false);
        assertEquals("Mine", localPushed.moveRemote.get(0).category());
        assertTrue(localPushed.localCategories.isEmpty());
    }

    @Test
    void uncategorizedEqualsRoot() {
        var plan = FeedReconciler.reconcile(
                List.of(local(1, "https://a.example/feed", "Uncategorized", 10L, false)),
                List.of(remote(10, "https://a.example/feed", null)), Set.of(), false);
        assertTrue(plan.localCategories.isEmpty());
        assertTrue(plan.moveRemote.isEmpty());
    }

    @Test
    void normalizesUrls() {
        assertEquals(FeedReconciler.normalizeUrl("HTTP://Example.com/feed/"),
                FeedReconciler.normalizeUrl("https://example.com/feed#x"));
    }
}
