package com.kindlerss.sync;

import java.util.Collection;
import java.util.List;

/**
 * A remote RSS service Extrablatt keeps feeds and read state in step with. Feeds
 * are addressed by category name, so providers map that onto their own folders
 * or tags.
 */
public interface SyncProvider {

    /** Throws {@link SyncException} if the server or credentials are not usable. */
    void testConnection();

    List<RemoteFeed> fetchFeeds();

    RemoteFeed addFeed(String url, String category);

    void removeFeed(long remoteFeedId);

    void moveFeed(long remoteFeedId, String category);

    /** Items changed since the cursor (a null cursor means everything). */
    ItemPage fetchChangedItems(String cursor);

    /** Every item of one feed, read or not, newest first, capped to a sane number. */
    List<RemoteItem> fetchFeedItems(long remoteFeedId, int limit);

    void setRead(Collection<Long> remoteItemIds, boolean read);

    /** Items plus the cursor to ask from next time. */
    record ItemPage(List<RemoteItem> items, String nextCursor) {}

    class SyncException extends RuntimeException {
        public SyncException(String message) {
            super(message);
        }

        public SyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
