package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON API for scripts and agents, authenticated by a personal API token (see
 * {@code SecurityConfig.apiFilterChain}) or, for the browser client, by the signed-in
 * session ({@code SecurityConfig.apiSessionFilterChain}). A thin layer over the same services the
 * website uses, always for the token's own account, so the limits, the address
 * checks on followed sites and the Kindle rules are the website's.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final FeedService feedService;
    private final ArticleService articleService;
    private final KindleMailService kindleMailService;
    private final UserService userService;
    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;
    private final int maxPageSize;

    public ApiController(FeedService feedService,
                         ArticleService articleService,
                         KindleMailService kindleMailService,
                         UserService userService,
                         EntitlementService entitlementService,
                         CurrentUser currentUser,
                         AppProperties properties) {
        this.feedService = feedService;
        this.articleService = articleService;
        this.kindleMailService = kindleMailService;
        this.userService = userService;
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
        this.maxPageSize = properties.articles().pageSize();
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        List<String> names = feedService.listFeeds(currentUser.requireId()).stream()
                .map(Feed::categoryName)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return Map.of("categories", names);
    }

    @GetMapping("/feeds")
    public Map<String, Object> feeds() {
        List<FeedView> feeds = feedService.listFeeds(currentUser.requireId()).stream()
                .map(FeedView::of).toList();
        return Map.of("feeds", feeds);
    }

    @PostMapping("/feeds")
    public ResponseEntity<FeedView> follow(@RequestBody FollowRequest request) {
        Feed feed = feedService.addFeed(currentUser.requireId(), request.url(),
                AppController.resolveCategory(request.category(), null));
        return ResponseEntity.status(HttpStatus.CREATED).body(FeedView.of(feed));
    }

    @DeleteMapping("/feeds/{id}")
    public ResponseEntity<Void> unfollow(@PathVariable("id") long id) {
        if (!feedService.deleteFeed(currentUser.requireId(), id)) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
        return ResponseEntity.noContent().build();
    }

    /** A blank or missing category means Uncategorized, as on the website. */
    @PutMapping("/feeds/{id}/category")
    public FeedView categorize(@PathVariable("id") long id,
                               @RequestBody(required = false) CategoryRequest request) {
        long userId = currentUser.requireId();
        String category = AppController.resolveCategory(request == null ? null : request.category(), null);
        if (!feedService.categorizeFeed(userId, id, category)) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
        return feedService.findById(userId, id)
                .map(FeedView::of)
                .orElseThrow(() -> new ArticleService.NotFoundException("Feed not found"));
    }

    @PostMapping("/categories/rename")
    public Map<String, Object> renameCategory(@RequestBody RenameCategoryRequest request) {
        int result = feedService.renameCategory(currentUser.requireId(),
                request.oldCategory(), request.newCategory());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("updated", Math.max(result, 0));
        body.put("unchanged", result == FeedService.CATEGORY_NAME_UNCHANGED);
        body.put("message", AppController.renameResult(result));
        return body;
    }

    @PostMapping("/feeds/{id}/read")
    public Map<String, Object> markFeedRead(@PathVariable("id") long id) {
        return Map.of("marked", articleService.markFeedRead(currentUser.requireId(), id));
    }

    /** The quick-start sites not followed yet. The address stays on the server. */
    @GetMapping("/feeds/suggestions")
    public Map<String, Object> suggestions() {
        List<SuggestionView> suggestions = feedService.defaultFeeds(currentUser.requireId()).stream()
                .map(feed -> new SuggestionView(feed.key(), feed.title(), feed.category()))
                .toList();
        return Map.of("suggestions", suggestions);
    }

    /** The website's loop: an unknown key or a site that fails is reported, the others still go in. */
    @PostMapping("/feeds/defaults")
    public Map<String, Object> followDefaults(@RequestBody(required = false) DefaultsRequest request) {
        List<String> keys = request == null ? null : request.keys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one newspaper or site");
        }
        long userId = currentUser.requireId();
        int added = 0;
        List<String> errors = new ArrayList<>();
        for (String key : keys) {
            var suggestion = feedService.defaultFeed(key);
            if (suggestion.isEmpty()) {
                errors.add("Unknown suggestion: " + key);
                continue;
            }
            try {
                var feed = suggestion.get();
                feedService.addFeed(userId, feed.url(), feed.category());
                added++;
            } catch (Exception e) {
                errors.add(suggestion.get().title() + ": " + UserMessages.of(e, "could not be added"));
            }
        }
        return Map.of("added", added, "errors", errors);
    }

    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(value = "unread", defaultValue = "false") boolean unread,
                                     @RequestParam(value = "feed", required = false) Long feedId,
                                     @RequestParam(value = "category", required = false) String category,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        long userId = currentUser.requireId();
        requireFeed(userId, feedId);
        int size = pageSize == null ? maxPageSize : Math.min(Math.max(pageSize, 1), maxPageSize);
        int current = Math.max(page, 1);
        Boolean unreadOnly = unread ? Boolean.TRUE : null;
        long total = articleService.count(userId, feedId, category, unreadOnly, null);
        int totalPages = (int) Math.max(1, (total + size - 1) / size);
        List<ItemView> items = articleService.findPage(userId, feedId, category, unreadOnly, null, current, size)
                .stream().map(ItemView::of).toList();
        return Map.of(
                "items", items,
                "page", current,
                "pageSize", size,
                "total", total,
                "totalPages", totalPages,
                "hasMore", current < totalPages);
    }

    @GetMapping("/articles/{id}")
    public ArticleView article(@PathVariable("id") long id,
                               @RequestParam(value = "images", defaultValue = "false") boolean images) {
        Article article = articleService.findById(currentUser.requireId(), id)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));
        return new ArticleView(ItemView.of(article), articleService.getReaderHtml(article, images),
                AppController.safeHttpUrl(article.url()),
                articleService.findCommentsUrl(article).map(AppController::safeHttpUrl).orElse(null));
    }

    @PostMapping("/articles/{id}/read")
    public Map<String, Object> markRead(@PathVariable("id") long id,
                                        @RequestBody(required = false) ReadRequest request) {
        boolean read = request == null || request.read() == null || request.read();
        Article article = articleService.markRead(currentUser.requireId(), id, read);
        return Map.of("id", article.id(), "read", article.read());
    }

    /**
     * Marks the unread articles of a feed or category (or of everything) read, up to
     * {@link AppController#MAX_MARK_READ} at a time; {@code hasMore} says whether to ask again.
     */
    @PostMapping("/items/mark-read")
    public Map<String, Object> markAllRead(@RequestBody(required = false) MarkReadRequest request) {
        long userId = currentUser.requireId();
        Long feedId = request == null ? null : request.feed();
        String category = request == null ? null : request.category();
        requireFeed(userId, feedId);
        List<Long> ids = new ArrayList<>();
        int page = 1;
        while (ids.size() <= AppController.MAX_MARK_READ) {
            List<Article> batch = articleService.findPage(userId, feedId, category, Boolean.TRUE, null, page++, 100);
            batch.forEach(article -> ids.add(article.id()));
            if (batch.size() < 100) {
                break;
            }
        }
        boolean hasMore = ids.size() > AppController.MAX_MARK_READ;
        List<Long> take = ids.stream().limit(AppController.MAX_MARK_READ).toList();
        int marked = take.isEmpty() ? 0 : articleService.markRead(userId, take, true);
        return Map.of("marked", marked, "hasMore", hasMore);
    }

    @PostMapping("/articles/{id}/send")
    public Map<String, Object> send(@PathVariable("id") long id,
                                    @RequestBody(required = false) SendRequest request) {
        kindleMailService.sendToKindle(currentUser.requireId(), id, request != null && request.images());
        return Map.of("message", "Sent to Kindle");
    }

    /** The article is kept even when sending fails, as on the website. */
    @PostMapping("/articles/from-url")
    public Map<String, Object> sendFromUrl(@RequestBody FromUrlRequest request) {
        long userId = currentUser.requireId();
        Article article = articleService.importFromUrl(userId, request.url());
        kindleMailService.sendToKindle(userId, article.id(), request.images());
        return Map.of("message", "Sent to Kindle", "articleId", article.id());
    }

    @GetMapping("/account")
    public Map<String, Object> account() {
        long userId = currentUser.requireId();
        var user = userService.findById(userId)
                .orElseThrow(() -> new ArticleService.NotFoundException("Account not found"));
        Entitlement entitlement = entitlementService.forUser(userId);
        Map<String, Object> plan = new java.util.LinkedHashMap<>();
        plan.put("plan", entitlement.plan().name());
        plan.put("maxFeeds", entitlement.maxFeeds());
        plan.put("maxSendsPerDay", entitlement.maxSendsPerDay());
        plan.put("maxSendsPerMonth", entitlement.maxSendsPerMonth());
        plan.put("newsletters", entitlement.newsletters());
        plan.put("trialEndsAt", entitlement.trialEndsAt());
        plan.put("onTrial", entitlement.onTrial());
        plan.put("trialEndsOn", AccountAdvice.formatTrialEnd(entitlement));
        Map<String, Object> account = new java.util.LinkedHashMap<>();
        account.put("email", user.email());
        account.put("emailVerified", user.emailVerified());
        account.put("kindleEmail", user.kindleEmail());
        account.put("kindleConfigured", user.kindleEmail() != null && !user.kindleEmail().isBlank());
        account.put("markReadOnNextPage", user.markReadOnNextPage());
        account.put("readTracking",
                ReadTracking.fromFlags(user.markReadOnNextPage(), user.markReadOnScroll()).parameter());
        account.put("entitlement", plan);
        return account;
    }

    /** Same validation as the settings form; a blank or missing address clears it. */
    @PutMapping("/account/kindle-email")
    public Map<String, Object> updateKindleEmail(@RequestBody(required = false) KindleEmailRequest request) {
        long userId = currentUser.requireId();
        userService.updateKindleEmail(userId, request == null ? null : request.kindleEmail());
        String saved = userService.findById(userId)
                .orElseThrow(() -> new ArticleService.NotFoundException("Account not found"))
                .kindleEmail();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("kindleEmail", saved);
        result.put("kindleConfigured", saved != null && !saved.isBlank());
        return result;
    }

    private void requireFeed(long userId, Long feedId) {
        if (feedId != null && feedService.findById(userId, feedId).isEmpty()) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
    }

    public record FollowRequest(String url, String category) {
    }

    public record ReadRequest(Boolean read) {
    }

    public record MarkReadRequest(Long feed, String category) {
    }

    public record SendRequest(boolean images) {
    }

    public record FromUrlRequest(String url, boolean images) {
    }

    public record KindleEmailRequest(String kindleEmail) {
    }

    public record CategoryRequest(String category) {
    }

    public record RenameCategoryRequest(String oldCategory, String newCategory) {
    }

    public record DefaultsRequest(List<String> keys) {
    }

    public record SuggestionView(String key, String title, String category) {
    }

    public record FeedView(Long id, String title, String url, String siteUrl, String category,
                           String source, long unreadCount, boolean hasError, String newsletterSender) {
        static FeedView of(Feed feed) {
            return new FeedView(feed.id(), feed.title(), feed.url(), feed.siteUrl(), feed.categoryName(),
                    feed.source().name(), feed.unreadCount(), feed.lastError() != null,
                    feed.newsletterSender());
        }
    }

    public record ItemView(Long id, Long feedId, String feedTitle, String title, String url, String author,
                           Instant publishedAt, boolean read, Instant sentAt) {
        static ItemView of(Article article) {
            return new ItemView(article.id(), article.feedId(), article.feedTitle(), article.title(),
                    article.url(), article.author(), article.publishedAt(), article.read(), article.sentAt());
        }
    }

    public record ArticleView(ItemView article, String contentHtml, String originalUrl, String commentsUrl) {
    }
}
