package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.FeedSource;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {

    private static final String FEED_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Example</title>
              <link>https://example.com/</link>
              <item>
                <title>First</title>
                <link>https://example.com/1</link>
                <guid isPermaLink="false">https://example.com/1</guid>
              </item>
            </channel></rss>
            """;

    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);
    private final RecordingTransactions transactions = new RecordingTransactions();

    private static final long UID = 7L;

    private FeedService service(int maxEntries) {
        return service(maxEntries, Runnable::run);
    }

    private FeedService service(int maxEntries, Executor refreshExecutor) {
        AppProperties properties = new AppProperties(
                "from@example.com", null, "remember-me",
                null, new AppProperties.Feeds(maxEntries), null, null, null, null, null);
        when(userRepository.findMarkReadOnNextPageByFeedId(anyLong())).thenReturn(Optional.of(true));
        // A real entitlement service, not a mock: with billing off it has to hand back
        // the configured app.limits values, which is exactly what these tests assume.
        EntitlementService entitlements = new EntitlementService(
                mock(SubscriptionRepository.class), mock(UserSendLimitRepository.class), properties);
        return new FeedService(feedRepository, articleRepository, userRepository, httpClient,
                new HtmlSanitizer(), entitlements, transactions, properties, refreshExecutor);
    }

    private static Feed feed(String url) {
        return new Feed(1L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static Feed newsletterFeed(String senderUrl) {
        return new Feed(2L, "A Newsletter", senderUrl, null, "Newsletters", null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.NEWSLETTER);
    }

    private static Answer<SafeHttpClient.FetchedContent> respondWithFeed() {
        return invocation -> new SafeHttpClient.FetchedContent(
                URI.create(invocation.getArgument(0)), FEED_XML, "application/rss+xml");
    }

    @Test
    void quickStartSuggestsGeneralNewspapers() {
        FeedService svc = service(100);
        assertEquals("BBC World News", svc.defaultFeed("bbc-world").orElseThrow().title());
        assertEquals("The Guardian", svc.defaultFeed("the-guardian").orElseThrow().title());
        assertEquals("NPR", svc.defaultFeed("npr").orElseThrow().title());
        assertEquals("Tagesschau", svc.defaultFeed("tagesschau").orElseThrow().title());
        assertEquals("Der Spiegel", svc.defaultFeed("spiegel").orElseThrow().title());
        assertTrue(svc.defaultFeed("hacker-news").isEmpty());
    }

    @Test
    void aFeedUrlIsAskedForMoreEntriesThanItPublishesByDefault() {
        assertEquals("https://hnrss.org/frontpage?count=100",
                FeedService.withEntryCount("https://hnrss.org/frontpage", 100));
        assertEquals("https://example.com/feed?format=rss&count=100",
                FeedService.withEntryCount("https://example.com/feed?format=rss", 100));
    }

    @Test
    void aUrlThatAlreadySaysHowManyEntriesItWantsIsLeftAlone() {
        assertEquals("https://hnrss.org/frontpage?count=5",
                FeedService.withEntryCount("https://hnrss.org/frontpage?count=5", 100));
        assertEquals("https://example.com/feed?limit=3",
                FeedService.withEntryCount("https://example.com/feed?limit=3", 100));
        assertEquals("https://example.com/feed",
                FeedService.withEntryCount("https://example.com/feed", 0));
        assertEquals("https://www.reddit.com/r/stuttgart/.rss",
                FeedService.withEntryCount("https://www.reddit.com/r/stuttgart/.rss", 100));
    }

    @Test
    void refreshMarksNewArticlesReadWhenTheOwnerDoesNotMarkOnTheNextPage() {
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(9L);
        FeedService svc = service(100);
        when(userRepository.findMarkReadOnNextPageByFeedId(1L)).thenReturn(Optional.of(false));
        when(userRepository.findIdByFeedId(1L)).thenReturn(Optional.of(UID));

        svc.refreshFeed(feed("https://example.com/feed"));

        verify(articleRepository).markRead(UID, 9L, true);
    }

    /**
     * Older German sites serve ISO-8859-1 or windows-1252 feeds as text/xml with no
     * charset parameter, and only the XML declaration says so. Decoding as UTF-8
     * first turned every umlaut into a replacement character.
     */
    @Test
    void aFeedIsDecodedWithTheEncodingItsXmlDeclarationNames() {
        assertEquals("Müller über Straßen", refreshedTitle(entryFeed("ISO-8859-1", "Müller über Straßen"),
                "ISO-8859-1", "text/xml"));
        assertEquals("Preis: 5 € – „gut“", refreshedTitle(entryFeed("windows-1252", "Preis: 5 € – „gut“"),
                "windows-1252", "application/rss+xml"));
    }

    @Test
    void aUtf8FeedWithAByteOrderMarkAndABareContentTypeIsReadAsUtf8() {
        byte[] body = entryFeedWithoutDeclaration("Grüße").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertEquals("Grüße", refreshedTitle(withBom, "text/xml"));
    }

    /** A charset the server names still counts when the document declares none. */
    @Test
    void aCharsetFromTheContentTypeIsUsedWhenTheFeedDeclaresNone() {
        byte[] body = entryFeedWithoutDeclaration("Käse").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertEquals("Käse", refreshedTitle(body, "application/xml; charset=ISO-8859-1"));
    }

    private static String entryFeed(String encoding, String title) {
        return "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>\n" + entryFeedWithoutDeclaration(title);
    }

    private static String entryFeedWithoutDeclaration(String title) {
        return """
                <rss version="2.0"><channel>
                  <title>Lokales</title>
                  <link>https://example.de/</link>
                  <item><title>%s</title><link>https://example.de/1</link><guid>g-1</guid></item>
                </channel></rss>
                """.formatted(title);
    }

    private String refreshedTitle(String xml, String encoding, String contentType) {
        return refreshedTitle(xml.getBytes(java.nio.charset.Charset.forName(encoding)), contentType);
    }

    private String refreshedTitle(byte[] bytes, String contentType) {
        String url = "https://example.de/feed";
        // What SafeHttpClient decodes for the HTML path: UTF-8, unless the header said otherwise.
        String decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        when(httpClient.get(anyString())).thenReturn(
                new SafeHttpClient.FetchedContent(URI.create(url), decoded, contentType, bytes));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(9L);

        service(0).refreshFeed(feed(url));

        org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(articleRepository).insert(anyLong(), eq("g-1"), title.capture(),
                anyString(), any(), any(), anyString(), anyString());
        org.mockito.Mockito.clearInvocations(articleRepository);
        return title.getValue();
    }

    /**
     * An entry already stored is skipped by the insert itself, so a refresh costs
     * one statement per entry rather than two, and a known entry is neither counted
     * nor marked read again.
     */
    @Test
    void refreshLeavesKnownEntriesToTheInsertInsteadOfCheckingFirst() {
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(-1L);
        FeedService svc = service(100);
        when(userRepository.findMarkReadOnNextPageByFeedId(1L)).thenReturn(Optional.of(false));
        when(userRepository.findIdByFeedId(1L)).thenReturn(Optional.of(UID));

        svc.refreshFeed(feed("https://example.com/feed"));

        verify(articleRepository, never()).existsByFeedIdAndGuid(anyLong(), anyString());
        verify(articleRepository, never()).markRead(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(feedRepository).clearError(1L);
    }

    /**
     * A popular feed followed by several accounts is fetched once per refresh, not
     * once per follower, and every follower still gets its entries.
     */
    @Test
    void aFeedFollowedByManyAccountsIsFetchedOnce() {
        String url = "https://example.com/feed";
        Feed mine = feed(url);
        Feed theirs = new Feed(4L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(mine, theirs));
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());

        service(0).refreshAll();

        verify(httpClient, times(1)).get(url);
        verify(articleRepository).insert(eq(1L), anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString());
        verify(articleRepository).insert(eq(4L), anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString());
        verify(feedRepository).clearError(1L);
        verify(feedRepository).clearError(4L);
    }

    /** A failed fetch is recorded once on each follower's feed, not twice. */
    @Test
    void aFailedFetchIsRecordedOnceOnEveryFeedAtThatAddress() {
        String url = "https://example.com/feed";
        Feed theirs = new Feed(4L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(feed(url), theirs));
        when(httpClient.get(anyString())).thenThrow(new SafeHttpClient.FetchException("HTTP 503"));

        service(0).refreshAll();

        verify(httpClient, times(1)).get(url);
        verify(feedRepository, times(1)).setError(1L, "HTTP 503");
        verify(feedRepository, times(1)).setError(4L, "HTTP 503");
    }

    /** One follower's write failing leaves the others' refresh in place. */
    @Test
    void aFailedWriteIsRecordedOnceAndDoesNotStopTheOtherFollowers() {
        String url = "https://example.com/feed";
        Feed theirs = new Feed(4L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(feed(url), theirs));
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());
        org.mockito.Mockito.doThrow(new IllegalStateException("deadlock detected"))
                .when(feedRepository).updateTitleAndSite(eq(1L), anyString(), anyString());

        service(0).refreshAll();

        verify(feedRepository, times(1)).setError(1L, "deadlock detected");
        verify(feedRepository, never()).setError(eq(4L), anyString());
        verify(feedRepository).clearError(4L);
    }

    /** A slow publisher must not hold a database connection while it answers. */
    @Test
    void refreshFetchesOutsideTheTransactionAndWritesInsideOne() {
        java.util.List<Boolean> fetchedInTransaction = new java.util.ArrayList<>();
        when(httpClient.get(anyString())).thenAnswer(invocation -> {
            fetchedInTransaction.add(transactions.active());
            return respondWithFeed().answer(invocation);
        });
        java.util.List<Boolean> writtenInTransaction = new java.util.ArrayList<>();
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
                    writtenInTransaction.add(transactions.active());
                    return 9L;
                });

        service(0).refreshFeed(feed("https://example.com/feed"));

        assertEquals(java.util.List.of(false), fetchedInTransaction);
        assertEquals(java.util.List.of(true), writtenInTransaction);
    }

    @Test
    void addingAFeedFetchesOutsideTheTransactionAndChecksTheLimitAgainInsideIt() {
        String url = "https://example.com/feed";
        java.util.List<Boolean> fetchedInTransaction = new java.util.ArrayList<>();
        when(httpClient.get(anyString())).thenAnswer(invocation -> {
            fetchedInTransaction.add(transactions.active());
            return respondWithFeed().answer(invocation);
        });
        // Room when the add starts; full by the time the fetch has finished.
        when(feedRepository.countByUser(UID)).thenReturn(0L, (long) AppProperties.Limits.DEFAULT_MAX_FEEDS);

        assertThrows(IllegalArgumentException.class, () -> service(0).addFeed(UID, url, null));

        assertEquals(java.util.List.of(false), fetchedInTransaction);
        assertEquals(1, transactions.started());
        verify(feedRepository, never()).insert(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void refreshAsksForTheConfiguredNumberOfEntries() {
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());

        service(100).refreshFeed(feed("https://hnrss.org/frontpage"));

        verify(httpClient).get("https://hnrss.org/frontpage?count=100");
        verify(articleRepository).insert(eq(1L), eq("https://example.com/1"), eq("First"),
                anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void aFeedThatRejectsTheCountIsFetchedAsItStands() {
        when(httpClient.get("https://example.com/feed?count=100"))
                .thenThrow(new SafeHttpClient.FetchException("HTTP 400"));
        when(httpClient.get("https://example.com/feed")).thenAnswer(respondWithFeed());

        service(100).refreshFeed(feed("https://example.com/feed"));

        verify(httpClient, times(2)).get(anyString());
        verify(articleRepository).insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void addingAFeedStoresTheFirstResponseWithoutFetchingItTwice() {
        String url = "https://www.reddit.com/r/stuttgart/.rss";
        when(httpClient.get(url)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(url), eq("https://example.com/"), eq("Local")))
                .thenReturn(feed(url));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(url)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        service(100).addFeed(UID, url, "Local");

        verify(httpClient, times(1)).get(url);
    }

    @Test
    void addingAnAddressWithoutASchemeFetchesItOverHttps() {
        when(httpClient.get("https://test.de/feed")).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq("https://test.de/feed"),
                eq("https://example.com/"), isNull())).thenReturn(feed("https://test.de/feed"));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed("https://test.de/feed")));

        Feed added = service(100).addFeed(UID, "test.de/feed", null);

        assertEquals("https://test.de/feed", added.url());
        verify(httpClient, never()).get("http://test.de/feed");
    }

    @Test
    void anAddressThatServesNoHttpsIsFetchedOverHttp() {
        when(httpClient.get("https://test.de"))
                .thenThrow(new SafeHttpClient.FetchException("Failed to fetch https://test.de"));
        when(httpClient.get("http://test.de")).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq("http://test.de"),
                eq("https://example.com/"), isNull())).thenReturn(feed("http://test.de"));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed("http://test.de")));

        Feed added = service(100).addFeed(UID, " test.de ", null);

        assertEquals("http://test.de", added.url());
    }

    @Test
    void anAddressThatAnswersOnNeitherSchemeReportsTheHttpsFailure() {
        when(httpClient.get("https://test.de"))
                .thenThrow(new SafeHttpClient.FetchException("DNS resolution failed for test.de"));
        when(httpClient.get("http://test.de"))
                .thenThrow(new SafeHttpClient.FetchException("Connection refused"));

        Exception failure = assertThrows(SafeHttpClient.FetchException.class,
                () -> service(100).addFeed(UID, "test.de", null));

        assertEquals("DNS resolution failed for test.de", failure.getMessage());
    }

    @Test
    void addingAHomepageDiscoversTheFeedItDeclares() {
        String homepage = "https://example.com/";
        String feedUrl = "https://example.com/feed.xml";
        String html = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\""
                + feedUrl + "\"></head><body>Hello</body></html>";
        when(httpClient.get(homepage))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create(homepage), html, "text/html"));
        when(httpClient.validateAndResolve(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        when(httpClient.get(feedUrl)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(feedUrl), eq("https://example.com/"), isNull()))
                .thenReturn(feed(feedUrl));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(feedUrl)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        Feed added = service(100).addFeed(UID, homepage, null);

        assertEquals(feedUrl, added.url());
        verify(httpClient).get(feedUrl);
    }

    @Test
    void addingAHomepageFallsBackToAWellKnownFeedPath() {
        String homepage = "https://example.com/";
        String feedUrl = "https://example.com/feed";
        String html = "<html><head><title>No feed links here</title></head><body>Hello</body></html>";
        when(httpClient.get(homepage))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create(homepage), html, "text/html"));
        when(httpClient.validateAndResolve(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        when(httpClient.get(feedUrl)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(feedUrl), eq("https://example.com/"), isNull()))
                .thenReturn(feed(feedUrl));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(feedUrl)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        Feed added = service(100).addFeed(UID, homepage, null);

        assertEquals(feedUrl, added.url());
        verify(httpClient).get(feedUrl);
    }

    @Test
    void receivingANewsletterIssueCreatesAFeedForItsSenderTheFirstTime() {
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.empty());
        when(feedRepository.countByUser(UID)).thenReturn(0L);
        when(feedRepository.findOrCreateNewsletterFeed(UID, "newsletter:editor@example.com",
                "The Editor", "Newsletters")).thenReturn(newsletterFeed("newsletter:editor@example.com"));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(false);
        when(articleRepository.insert(eq(2L), eq("message-1"), eq("Issue #1"), isNull(), eq("The Editor"),
                any(), isNull(), anyString())).thenReturn(42L);

        long id = service(100).receiveNewsletterIssue(UID, "Editor@Example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(42L, id);
        verify(feedRepository).clearError(2L);
    }

    @Test
    void receivingANewsletterIssueFromAKnownSenderReusesItsFeed() {
        Feed existing = newsletterFeed("newsletter:editor@example.com");
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.of(existing));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-2")).thenReturn(false);
        when(articleRepository.insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyString())).thenReturn(43L);

        service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-2", "Issue #2", Instant.EPOCH, "<p>Hello again</p>");

        verify(feedRepository, never()).findOrCreateNewsletterFeed(anyLong(), anyString(), any(), any());
    }

    @Test
    void receivingTheSameNewsletterIssueTwiceIsIgnored() {
        Feed existing = newsletterFeed("newsletter:editor@example.com");
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.of(existing));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(true);

        long id = service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(FeedService.NEWSLETTER_DUPLICATE, id);
        verify(articleRepository, never()).insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyString());
    }

    @Test
    void aNewSenderIsDroppedOncePerAccountFeedLimitIsReached() {
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.empty());
        when(feedRepository.countByUser(UID)).thenReturn(50L);

        long id = service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(FeedService.NEWSLETTER_FEED_LIMIT_REACHED, id);
        verify(feedRepository, never()).findOrCreateNewsletterFeed(anyLong(), anyString(), any(), any());
    }

    @Test
    void refreshingAllFeedsSkipsNewslettersSinceTheyHaveNothingToPoll() {
        Feed newsletter = newsletterFeed("newsletter:editor@example.com");
        Feed clipping = new Feed(3L, "Pasted URLs", Feed.CLIPPING_URL, null, "Pasted", null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.CLIPPING);
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(newsletter, clipping));

        service(100).refreshAll();

        verify(httpClient, never()).get(anyString());
    }

    @Test
    void openingTheReaderPollsThatAccountOnceUntilTheCooldownPasses() {
        when(feedRepository.findAll(UID)).thenReturn(java.util.List.of(feed("https://example.com/feed")));
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());

        FeedService service = service(100);
        service.refreshForUserSoon(UID);
        service.refreshForUserSoon(UID);

        verify(feedRepository, times(1)).findAll(UID);
        verify(httpClient, times(1)).get(anyString());
    }

    @Test
    void renamingACategoryUpdatesEveryFeedThatHasIt() {
        when(feedRepository.renameCategory(UID, "Technology", "Tech")).thenReturn(3);

        int updated = service(100).renameCategory(UID, "Technology", "Tech");

        assertEquals(3, updated);
        verify(feedRepository).renameCategory(UID, "Technology", "Tech");
    }

    @Test
    void renamingUncategorizedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "Uncategorized", "Tech"));
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "", "Tech"));
    }

    @Test
    void renamingToABlankNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "Technology", "  "));
    }

    @Test
    void renamingACategoryToItsOwnNameIsANoOp() {
        int updated = service(100).renameCategory(UID, "Technology", " Technology ");

        assertEquals(FeedService.CATEGORY_NAME_UNCHANGED, updated);
        verify(feedRepository, never()).renameCategory(anyLong(), anyString(), anyString());
    }

    @Test
    void changingOnlyACategorysCapitalizationIsARename() {
        when(feedRepository.renameCategory(UID, "technology", "Technology")).thenReturn(2);

        int updated = service(100).renameCategory(UID, "technology", "Technology");

        assertEquals(2, updated);
        verify(feedRepository).renameCategory(UID, "technology", "Technology");
    }
}
