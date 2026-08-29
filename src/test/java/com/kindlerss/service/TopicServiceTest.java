package com.kindlerss.service;

import com.kindlerss.domain.Feed;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicServiceTest {

    private final TopicCatalog catalog = new TopicCatalog();
    private final FeedService feedService = mock(FeedService.class);
    private final TopicService service = new TopicService(catalog, feedService);

    @Test
    void followingATopicAddsEverySourceUnderItsName() {
        when(feedService.addFeed(eq(1L), anyString(), anyString()))
                .thenReturn(new Feed(1L, "A source", "https://example.com/feed", null, null, null, null));

        TopicCatalog.Topic blindness = catalog.find("blindness").orElseThrow();
        TopicService.SubscribeResult result = service.subscribe(1L, "blindness");

        assertEquals(blindness.sources().size(), result.added());
        assertTrue(result.problems().isEmpty());
        for (TopicCatalog.Source source : blindness.sources()) {
            verify(feedService).addFeed(1L, source.url(), blindness.name());
        }
    }

    @Test
    void oneUnreachableSourceDoesNotStopTheRest() {
        when(feedService.addFeed(eq(1L), anyString(), anyString()))
                .thenReturn(new Feed(1L, "A source", "https://example.com/feed", null, null, null, null));
        when(feedService.addFeed(eq(1L), eq("https://webaim.org/blog/feed/"), anyString()))
                .thenThrow(new IllegalArgumentException("Could not find an RSS/Atom feed at that address."));

        TopicService.SubscribeResult result = service.subscribe(1L, "accessibility");

        assertEquals(3, result.added());
        assertEquals(java.util.List.of("WebAIM"), result.problems());
        assertTrue(result.summary().startsWith("Added 3 sources to"));
    }

    @Test
    void sourcesAlreadyFollowedAreCountedRatherThanReportedAsFailures() {
        when(feedService.addFeed(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Feed already exists"));

        TopicService.SubscribeResult result = service.subscribe(1L, "science");

        assertEquals(0, result.added());
        assertTrue(result.problems().isEmpty());
        assertTrue(result.summary().contains("already follow"));
    }

    @Test
    void anUnknownTopicIsRejectedRatherThanSilentlyDoingNothing() {
        assertThrows(IllegalArgumentException.class, () -> service.subscribe(1L, "astrology"));
    }

    @Test
    void aWebsiteCanBeTypedTheWayPeopleSayIt() {
        assertEquals("https://bbc.com", TopicService.normalizeAddress("bbc.com"));
        assertEquals("https://www.statnews.com", TopicService.normalizeAddress("  www.statnews.com  "));
        assertEquals("https://nfb.org/blog/", TopicService.normalizeAddress("nfb.org/blog/"));
        assertEquals("https://applevis.com", TopicService.normalizeAddress("<https://applevis.com>"));
        assertEquals("http://example.org/feed.xml", TopicService.normalizeAddress("http://example.org/feed.xml"));
    }

    @Test
    void somethingThatIsNotAnAddressIsSaidSoInWordsThatHelp() {
        IllegalArgumentException empty =
                assertThrows(IllegalArgumentException.class, () -> TopicService.normalizeAddress("  "));
        assertTrue(empty.getMessage().contains("bbc.com"));

        IllegalArgumentException prose =
                assertThrows(IllegalArgumentException.class, () -> TopicService.normalizeAddress("blindness news"));
        assertTrue(prose.getMessage().contains("does not look like a website address"));
    }

    @Test
    void theCatalogueIsInternallyConsistent() {
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (TopicCatalog.Topic topic : catalog.topics()) {
            assertTrue(keys.add(topic.key()), "duplicate topic key: " + topic.key());
            assertTrue(names.add(topic.name().toLowerCase()), "duplicate topic name: " + topic.name());
            assertFalse(topic.description().isBlank(), topic.key() + " has no description");
            assertFalse(topic.sources().isEmpty(), topic.key() + " has no sources");
            for (TopicCatalog.Source source : topic.sources()) {
                assertTrue(source.url().startsWith("https://"), source.url() + " is not https");
                assertFalse(source.title().isBlank());
                assertFalse(source.description().isBlank(), source.url() + " has no description");
            }
        }
        // The two subjects this edition exists for are in it by name.
        assertTrue(catalog.find("blindness").isPresent());
        assertTrue(catalog.find("clinical-trials").isPresent());
        assertTrue(catalog.findByName("Blindness and low vision").isPresent());
        assertTrue(catalog.find("").isEmpty());
    }
}
