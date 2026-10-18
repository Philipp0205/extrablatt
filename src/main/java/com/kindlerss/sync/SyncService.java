package com.kindlerss.sync;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.SyncAccount;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.SyncRepository;
import com.kindlerss.repository.SyncRepository.DirtyArticle;
import com.kindlerss.repository.SyncRepository.SyncArticle;
import com.kindlerss.repository.SyncRepository.SyncFeed;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.SafeHttpClient;
import com.kindlerss.sync.SyncProvider.ItemPage;
import com.kindlerss.sync.SyncProvider.SyncException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps an account's feeds and read state in step with an external provider.
 * The provider wins conflicts; extraction, delivery and article fetching stay here.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** How far back to look for freshly stored articles the provider has not been matched to. */
    private static final Duration RECENT_WINDOW = Duration.ofDays(3);
    static final Duration BACKGROUND_COOLDOWN = Duration.ofMinutes(10);

    private final SyncRepository syncRepository;
    private final FeedRepository feedRepository;
    private final FeedService feedService;
    private final EntitlementService entitlements;
    private final SecretCipher cipher;
    private final SafeHttpClient safeHttp;
    private final AppProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "provider-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Instant> lastBackground = new ConcurrentHashMap<>();

    public SyncService(SyncRepository syncRepository, FeedRepository feedRepository, FeedService feedService,
                       EntitlementService entitlements, SecretCipher cipher, SafeHttpClient safeHttp,
                       AppProperties properties) {
        this.syncRepository = syncRepository;
        this.feedRepository = feedRepository;
        this.feedService = feedService;
        this.entitlements = entitlements;
        this.cipher = cipher;
        this.safeHttp = safeHttp;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public boolean available() {
        return cipher.available();
    }

    public Optional<SyncAccount> account(long userId) {
        return syncRepository.findAccount(userId);
    }

    /** Verifies the credentials, stores the connection and starts the first sync in the background. */
    public void connect(long userId, String baseUrl, String username, String password) {
        if (!cipher.available()) {
            throw new IllegalArgumentException("Sync is not configured on this server");
        }
        String base = NextcloudNewsProvider.normalizeBase(baseUrl);
        if (base.isEmpty() || username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Server address, username and app password are all required");
        }
        URI uri;
        try {
            uri = safeHttp.validateAndResolve(base, properties.sync().allowPrivateHosts());
        } catch (SafeHttpClient.FetchException e) {
            throw new IllegalArgumentException("That server address cannot be used: " + e.getMessage());
        }
        try {
            newProvider(base, username.trim(), password).testConnection();
        } catch (SyncException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        syncRepository.saveAccount(userId, SyncAccount.NEXTCLOUD, uri.toString().replaceAll("/+$", ""),
                username.trim(), cipher.encrypt(password));
        syncSoon(userId);
    }

    public void disconnect(long userId) {
        syncRepository.deleteAccount(userId);
    }

    /** Runs a sync for the account on the background thread, if one is not already running. */
    public void syncSoon(long userId) {
        if (syncRepository.findAccount(userId).isEmpty() || inFlight.contains(userId)) {
            return;
        }
        executor.execute(() -> sync(userId));
    }

    /** Like {@link #syncSoon} but at most once per cooldown; for page visits. */
    public void syncSoonThrottled(long userId) {
        Instant now = Instant.now();
        Instant previous = lastBackground.get(userId);
        if (previous != null && previous.isAfter(now.minus(BACKGROUND_COOLDOWN))) {
            return;
        }
        if (syncRepository.findAccount(userId).isEmpty()) {
            return;
        }
        lastBackground.put(userId, now);
        syncSoon(userId);
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT3M")
    public void scheduledSync() {
        for (SyncAccount account : syncRepository.findAllAccounts()) {
            sync(account.userId());
        }
    }

    /** Full sync for one account. Never throws; failures land in the account's status. */
    public void sync(long userId) {
        if (!inFlight.add(userId)) {
            return;
        }
        try {
            Optional<SyncAccount> found = syncRepository.findAccount(userId);
            if (found.isEmpty()) {
                return;
            }
            SyncAccount account = found.get();
            SyncProvider provider = providerFor(account);
            boolean first = account.cursor() == null;
            int skipped = syncFeeds(userId, provider, first);
            ItemPage page = syncItems(userId, provider, account.cursor(), first);
            syncRepository.markSynced(userId, page.nextCursor());
            if (skipped > 0) {
                syncRepository.setError(userId, skipped + " feed(s) from the provider were not added "
                        + "because your feed limit was reached.");
            }
        } catch (RuntimeException e) {
            log.warn("Sync failed for user {}: {}", userId, e.getMessage());
            syncRepository.setError(userId, e.getMessage());
        } finally {
            inFlight.remove(userId);
        }
    }

    /** Returns how many remote feeds could not be created locally because of the feed limit. */
    int syncFeeds(long userId, SyncProvider provider, boolean first) {
        List<SyncFeed> local = syncRepository.findRssFeeds(userId);
        List<RemoteFeed> remote = provider.fetchFeeds();
        Set<Long> tombstones = first ? Set.of() : new HashSet<>(syncRepository.findTombstones(userId));
        FeedReconciler.Plan plan = FeedReconciler.reconcile(local, remote, tombstones, first);

        for (var link : plan.links) {
            syncRepository.setFeedRemote(link.localId(), link.remoteId());
        }
        for (var change : plan.localCategories) {
            syncRepository.setFeedCategory(change.localId(), change.category());
        }
        for (Long feedId : plan.deleteLocal) {
            syncRepository.deleteFeed(feedId);
        }
        for (Long remoteId : plan.deleteRemote) {
            attempt("remove feed " + remoteId, () -> provider.removeFeed(remoteId));
        }
        for (var move : plan.moveRemote) {
            attempt("move feed " + move.remoteId(), () -> {
                provider.moveFeed(move.remoteId(), move.category());
                syncRepository.clearCategoryDirty(move.localId());
            });
        }
        for (SyncFeed feed : plan.createRemote) {
            attempt("add feed " + feed.url(), () -> {
                RemoteFeed created = provider.addFeed(feed.url(), feed.category());
                syncRepository.setFeedRemote(feed.id(), created.id());
                syncRepository.clearCategoryDirty(feed.id());
            });
        }
        syncRepository.clearTombstones(userId);

        int skipped = 0;
        boolean created = false;
        Entitlement entitlement = entitlements.forUser(userId);
        for (RemoteFeed r : plan.createLocal) {
            if (feedRepository.countByUser(userId) >= entitlement.maxFeeds()) {
                skipped++;
                continue;
            }
            try {
                String title = r.title() == null || r.title().isBlank() ? r.url() : r.title();
                Feed feed = feedRepository.insert(userId, title, r.url(), null, r.category());
                syncRepository.setFeedRemote(feed.id(), r.id());
                syncRepository.clearCategoryDirty(feed.id());
                created = true;
            } catch (RuntimeException e) {
                log.warn("Could not add synced feed {} for user {}: {}", r.url(), userId, e.getMessage());
            }
        }
        if (created) {
            // Give the new feeds their articles now so their read state can be matched.
            feedService.refreshForUser(userId);
        }
        return skipped;
    }

    ItemPage syncItems(long userId, SyncProvider provider, String cursor, boolean first) {
        if (first) {
            syncRepository.markReadArticlesDirty(userId);
        }
        List<SyncArticle> local = syncRepository.findLinkedArticles(userId);
        Matcher matcher = new Matcher(local);

        ItemPage page = provider.fetchChangedItems(cursor);
        Set<Long> handled = new HashSet<>();
        for (RemoteItem item : page.items()) {
            SyncArticle article = matcher.find(item);
            if (article == null) {
                continue;
            }
            handled.add(article.id());
            if (first && article.dirty() && article.read() && !item.read()) {
                syncRepository.linkArticle(article.id(), item.id()); // merge: read here stays read
            } else if (article.read() != item.read() || article.dirty() || !Long.valueOf(item.id()).equals(article.remoteId())) {
                syncRepository.applyRemoteState(article.id(), item.id(), item.read());
            }
        }

        Instant since = Instant.now().minus(RECENT_WINDOW);
        for (Long remoteFeedId : syncRepository.findFeedsWithRecentUnmatchedArticles(userId, since)) {
            List<RemoteItem> items;
            try {
                items = provider.fetchFeedItems(remoteFeedId, 500);
            } catch (SyncException e) {
                log.debug("Could not look up items of remote feed {}: {}", remoteFeedId, e.getMessage());
                continue;
            }
            for (RemoteItem item : items) {
                SyncArticle article = matcher.find(item);
                if (article == null || article.remoteId() != null || handled.contains(article.id())) {
                    continue;
                }
                handled.add(article.id());
                if (article.dirty()) {
                    syncRepository.linkArticle(article.id(), item.id());
                } else {
                    syncRepository.applyRemoteState(article.id(), item.id(), item.read());
                }
            }
        }

        pushReadState(userId, provider);
        return page;
    }

    private void pushReadState(long userId, SyncProvider provider) {
        List<Long> read = new ArrayList<>();
        List<Long> unread = new ArrayList<>();
        Map<Long, Long> articleByRemote = new HashMap<>();
        for (DirtyArticle dirty : syncRepository.findDirtyArticles(userId)) {
            articleByRemote.put(dirty.remoteId(), dirty.id());
            (dirty.read() ? read : unread).add(dirty.remoteId());
        }
        if (!read.isEmpty()) {
            provider.setRead(read, true);
            syncRepository.clearDirty(read.stream().map(articleByRemote::get).toList(), true);
        }
        if (!unread.isEmpty()) {
            provider.setRead(unread, false);
            syncRepository.clearDirty(unread.stream().map(articleByRemote::get).toList(), false);
        }
    }

    private void attempt(String what, Runnable action) {
        try {
            action.run();
        } catch (SyncException e) {
            log.warn("Sync could not {}: {}", what, e.getMessage());
        }
    }

    private SyncProvider providerFor(SyncAccount account) {
        return newProvider(account.baseUrl(), account.username(), cipher.decrypt(account.secretEncrypted()));
    }

    private SyncProvider newProvider(String baseUrl, String username, String password) {
        return new NextcloudNewsProvider(baseUrl, username, password, safeHttp,
                properties.http().connectTimeout(), properties.http().readTimeout(),
                properties.sync().allowPrivateHosts());
    }

    /** Finds the local article for a remote item: by guid, else by address, within the same feed. */
    static final class Matcher {
        private final Map<String, SyncArticle> byGuid = new HashMap<>();
        private final Map<String, SyncArticle> byUrl = new HashMap<>();

        Matcher(List<SyncArticle> articles) {
            for (SyncArticle article : articles) {
                if (article.guid() != null) {
                    byGuid.putIfAbsent(article.feedRemoteId() + "\n" + article.guid(), article);
                }
                if (article.url() != null) {
                    byUrl.putIfAbsent(article.feedRemoteId() + "\n" + article.url(), article);
                }
            }
        }

        SyncArticle find(RemoteItem item) {
            SyncArticle match = item.guid() == null ? null : byGuid.get(item.feedId() + "\n" + item.guid());
            if (match == null && item.url() != null) {
                match = byUrl.get(item.feedId() + "\n" + item.url());
            }
            return match;
        }
    }
}
