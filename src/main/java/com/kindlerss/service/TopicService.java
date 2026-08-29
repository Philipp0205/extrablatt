package com.kindlerss.service;

import com.kindlerss.domain.Feed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Subscribing by subject rather than by URL.
 *
 * <p>One source that has gone away must not stop a reader from following a topic,
 * so every source in a topic is attempted on its own and the outcome is reported
 * as a whole: how many were added, how many were already there, and which ones
 * could not be reached.
 */
@Service
public class TopicService {

    private static final Logger log = LoggerFactory.getLogger(TopicService.class);

    /** A bare host with at least one dot, optionally with a path — what people actually type. */
    private static final Pattern LOOKS_LIKE_ADDRESS =
            Pattern.compile("^[\\w.-]+\\.[a-z]{2,}(?::\\d+)?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);

    private final TopicCatalog catalog;
    private final FeedService feedService;

    public TopicService(TopicCatalog catalog, FeedService feedService) {
        this.catalog = catalog;
        this.feedService = feedService;
    }

    /** What subscribing to a whole topic did, in terms a reader can be told directly. */
    public record SubscribeResult(String topicName, int added, int alreadyFollowed, List<String> problems) {

        public boolean addedAnything() {
            return added > 0;
        }

        /** One sentence describing the outcome, ready to be read out. */
        public String summary() {
            if (added > 0 && alreadyFollowed > 0) {
                return "Added " + count(added) + " to " + topicName + ". "
                        + count(alreadyFollowed) + (alreadyFollowed == 1 ? " was" : " were")
                        + " already there.";
            }
            if (added > 0) {
                return "Added " + count(added) + " to " + topicName + ".";
            }
            if (alreadyFollowed > 0) {
                return "You already follow every source in " + topicName + ".";
            }
            return "Nothing could be added to " + topicName + " just now.";
        }

        private static String count(int number) {
            return number == 1 ? "1 source" : number + " sources";
        }
    }

    public SubscribeResult subscribe(long userId, String topicKey) {
        TopicCatalog.Topic topic = catalog.find(topicKey)
                .orElseThrow(() -> new IllegalArgumentException("That topic is not on the list"));
        int added = 0;
        int alreadyFollowed = 0;
        List<String> problems = new ArrayList<>();
        for (TopicCatalog.Source source : topic.sources()) {
            try {
                feedService.addFeed(userId, source.url(), topic.name());
                added++;
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.toLowerCase(Locale.ROOT).contains("already exists")) {
                    alreadyFollowed++;
                } else {
                    log.info("Could not add {} for user {}: {}", source.url(), userId, message);
                    problems.add(source.title());
                }
            }
        }
        return new SubscribeResult(topic.name(), added, alreadyFollowed, problems);
    }

    /**
     * Follows one website from whatever the reader typed — "bbc.com", with or
     * without {@code https://}, with or without a trailing slash. Feed discovery
     * takes it from there, so nobody has to find an XML link first.
     */
    public Feed addWebsite(long userId, String address, String topicName) {
        return feedService.addFeed(userId, normalizeAddress(address), topicName);
    }

    static String normalizeAddress(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Type the address of a website first, for example bbc.com");
        }
        // People paste addresses out of e-mails and browsers, brackets and all.
        value = value.replaceAll("^[<(\\[\"']+", "").replaceAll("[>)\\]\"']+$", "").trim();
        String withoutScheme = value.replaceFirst("(?i)^https?://", "");
        if (!LOOKS_LIKE_ADDRESS.matcher(withoutScheme).matches()) {
            throw new IllegalArgumentException(
                    "That does not look like a website address. Try something like bbc.com");
        }
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? value : "https://" + value;
    }
}
