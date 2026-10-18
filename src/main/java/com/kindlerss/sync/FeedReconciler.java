package com.kindlerss.sync;

import com.kindlerss.repository.SyncRepository.SyncFeed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides how the local and remote feed lists are brought together. Pure: it only
 * compares, and returns what to do. The provider wins every conflict, except that
 * a category the reader just changed here is pushed once, and that on the first
 * connection the two lists are merged rather than one replacing the other.
 */
public final class FeedReconciler {

    public record Link(long localId, long remoteId) {}

    public record CategoryChange(long localId, String category) {}

    public record RemoteMove(long remoteId, long localId, String category) {}

    public static final class Plan {
        public final List<Link> links = new ArrayList<>();
        public final List<RemoteFeed> createLocal = new ArrayList<>();
        public final List<CategoryChange> localCategories = new ArrayList<>();
        public final List<Long> deleteLocal = new ArrayList<>();
        public final List<SyncFeed> createRemote = new ArrayList<>();
        public final List<Long> deleteRemote = new ArrayList<>();
        public final List<RemoteMove> moveRemote = new ArrayList<>();
    }

    private FeedReconciler() {}

    public static Plan reconcile(List<SyncFeed> local, List<RemoteFeed> remote, Set<Long> tombstones,
                                 boolean firstSync) {
        Plan plan = new Plan();
        Map<Long, SyncFeed> localByRemoteId = new HashMap<>();
        Map<String, SyncFeed> localByUrl = new HashMap<>();
        for (SyncFeed feed : local) {
            if (feed.remoteId() != null) {
                localByRemoteId.put(feed.remoteId(), feed);
            }
            localByUrl.putIfAbsent(normalizeUrl(feed.url()), feed);
        }

        Set<Long> matchedLocal = new HashSet<>();
        for (RemoteFeed r : remote) {
            SyncFeed match = localByRemoteId.get(r.id());
            if (match == null) {
                SyncFeed byUrl = localByUrl.get(normalizeUrl(r.url()));
                if (byUrl != null && !matchedLocal.contains(byUrl.id())
                        && (byUrl.remoteId() == null || byUrl.remoteId() == r.id())) {
                    match = byUrl;
                }
            }
            if (match != null) {
                if (!matchedLocal.add(match.id())) {
                    continue; // the provider lists the same feed twice
                }
                if (match.remoteId() == null || match.remoteId() != r.id()) {
                    plan.links.add(new Link(match.id(), r.id()));
                }
                if (!sameCategory(match.category(), r.category())) {
                    // On a first connection a category only we have is kept and pushed.
                    boolean keepLocal = match.categoryDirty()
                            || (firstSync && canonicalCategory(r.category()).isEmpty());
                    if (keepLocal) {
                        plan.moveRemote.add(new RemoteMove(r.id(), match.id(), match.category()));
                    } else {
                        plan.localCategories.add(new CategoryChange(match.id(), emptyToNull(r.category())));
                    }
                }
            } else if (tombstones.contains(r.id())) {
                plan.deleteRemote.add(r.id());
            } else {
                plan.createLocal.add(r);
            }
        }

        for (SyncFeed feed : local) {
            if (matchedLocal.contains(feed.id())) {
                continue;
            }
            if (feed.remoteId() != null) {
                plan.deleteLocal.add(feed.id()); // gone from the provider
            } else {
                plan.createRemote.add(feed);
            }
        }
        return plan;
    }

    public static boolean sameCategory(String a, String b) {
        return canonicalCategory(a).equals(canonicalCategory(b));
    }

    private static String canonicalCategory(String category) {
        String trimmed = category == null ? "" : category.trim();
        if (trimmed.equalsIgnoreCase("Uncategorized")) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String emptyToNull(String category) {
        return category == null || category.isBlank() ? null : category.trim();
    }

    /** Scheme-insensitive, trailing-slash-insensitive form used to recognize the same feed. */
    public static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim();
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        normalized = normalized.replaceFirst("^(?i)https?://", "");
        int slash = normalized.indexOf('/');
        String host = slash < 0 ? normalized : normalized.substring(0, slash);
        String rest = slash < 0 ? "" : normalized.substring(slash);
        return (host.toLowerCase(Locale.ROOT) + rest).replaceAll("/+$", "");
    }
}
