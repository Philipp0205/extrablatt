package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** MVC endpoints for feeds, articles, and Kindle send actions. */
@Controller
public class AppController {

    /** How much of a feed title a filter button carries. */
    private static final int FILTER_LABEL_MAX = 18;

    /** Feeds that were never put in a category are browsed last. */
    private static final Comparator<String> CATEGORY_ORDER =
            Comparator.comparing((String name) -> Feed.UNCATEGORIZED.equals(name))
                    .thenComparing(Comparator.<String>naturalOrder());

    private final FeedService feedService;
    private final ArticleService articleService;
    private final KindleMailService kindleMailService;
    private final CurrentUser currentUser;
    private final int pageSize;

    public AppController(FeedService feedService,
                         ArticleService articleService,
                         KindleMailService kindleMailService,
                         CurrentUser currentUser,
                         AppProperties properties) {
        this.feedService = feedService;
        this.articleService = articleService;
        this.kindleMailService = kindleMailService;
        this.currentUser = currentUser;
        this.pageSize = properties.articles().pageSize();
    }

    @GetMapping("/")
    public String home(Model model) {
        long userId = currentUser.requireId();
        List<Feed> feeds = feedService.listFeeds(userId);
        long totalUnread = feeds.stream().mapToLong(Feed::unreadCount).sum();
        model.addAttribute("feeds", feeds);
        Map<String, List<Feed>> feedGroups = new LinkedHashMap<>();
        for (Feed feed : feeds) {
            feedGroups.computeIfAbsent(feed.categoryName(), ignored -> new ArrayList<>()).add(feed);
        }
        model.addAttribute("feedGroups", feedGroups);
        model.addAttribute("defaultFeeds", feedService.defaultFeeds(userId));
        model.addAttribute("totalUnread", totalUnread);
        return "index";
    }

    @PostMapping("/feeds")
    public String addFeed(@RequestParam("url") String url,
                          @RequestParam(value = "category", required = false) String category,
                          RedirectAttributes redirectAttributes) {
        try {
            Feed feed = feedService.addFeed(currentUser.requireId(), url, category);
            redirectAttributes.addFlashAttribute("message", "Added feed: " + feed.title());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/feeds/defaults")
    public String addDefaultFeeds(@RequestParam(value = "feed", required = false) List<String> keys,
                                  RedirectAttributes redirectAttributes) {
        if (keys == null || keys.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Choose at least one suggested feed");
            return "redirect:/";
        }
        long userId = currentUser.requireId();
        int added = 0;
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        for (String key : keys) {
            var suggestion = feedService.defaultFeed(key);
            if (suggestion.isEmpty()) {
                errors.add("Unknown suggested feed: " + key);
                continue;
            }
            try {
                var feed = suggestion.get();
                feedService.addFeed(userId, feed.url(), feed.category());
                added++;
            } catch (Exception e) {
                errors.add(suggestion.get().title() + ": " + e.getMessage());
            }
        }
        if (added > 0) {
            redirectAttributes.addFlashAttribute("message",
                    added == 1 ? "Added 1 suggested feed" : "Added " + added + " suggested feeds");
        }
        if (!errors.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", String.join("; ", errors));
        }
        return "redirect:/";
    }

    @PostMapping("/feeds/{id}/category")
    public String categorizeFeed(@PathVariable("id") long id,
                                 @RequestParam(value = "category", required = false) String category,
                                 RedirectAttributes redirectAttributes) {
        if (feedService.categorizeFeed(currentUser.requireId(), id, category)) {
            redirectAttributes.addFlashAttribute("message", "Feed category updated");
        } else {
            redirectAttributes.addFlashAttribute("error", "Feed not found");
        }
        return "redirect:/";
    }

    @PostMapping("/feeds/{id}/delete")
    public String deleteFeed(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        if (!feedService.deleteFeed(currentUser.requireId(), id)) {
            redirectAttributes.addFlashAttribute("error", "Feed not found");
        } else {
            redirectAttributes.addFlashAttribute("message", "Feed deleted");
        }
        return "redirect:/";
    }

    @PostMapping("/refresh")
    public String refresh(RedirectAttributes redirectAttributes) {
        try {
            feedService.refreshForUser(currentUser.requireId());
            redirectAttributes.addFlashAttribute("message", "Feeds refreshed");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Refresh failed: " + e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/items")
    public String items(@RequestParam(value = "feed", required = false) Long feedId,
                        @RequestParam(value = "category", required = false) String category,
                        @RequestParam(value = "unread", required = false) Boolean unread,
                        @RequestParam(value = "snapshot", required = false) Long snapshot,
                        @RequestParam(value = "page", defaultValue = "1") int page,
                        Model model) {
        long userId = currentUser.requireId();
        if (feedId != null && feedService.findById(userId, feedId).isEmpty()) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
        Boolean unreadOnly = Boolean.TRUE.equals(unread) ? Boolean.TRUE : null;
        if (Boolean.TRUE.equals(unread) && snapshot == null) {
            return "redirect:" + itemsPath(feedId, category, true, Math.max(page, 1),
                    System.currentTimeMillis());
        }
        Instant unreadSnapshot = Boolean.TRUE.equals(unread) && snapshot != null
                ? Instant.ofEpochMilli(Math.min(snapshot, System.currentTimeMillis())) : null;
        long total = category == null && unreadSnapshot == null
                ? articleService.count(userId, feedId, unreadOnly)
                : articleService.count(userId, feedId, category, unreadOnly, unreadSnapshot);
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        // Marking a page read shrinks an unread list, so a page number can end up
        // past the end; show the last page rather than an empty one.
        int safePage = Math.min(Math.max(page, 1), totalPages);
        List<Article> articles = category == null && unreadSnapshot == null
                ? articleService.findPage(userId, feedId, unreadOnly, safePage, pageSize)
                : articleService.findPage(userId, feedId, category, unreadOnly, unreadSnapshot, safePage, pageSize);

        model.addAttribute("articles", articles);
        addFilterBar(model, feedService.listFeeds(userId), feedId, category, Boolean.TRUE.equals(unread));
        model.addAttribute("feedId", feedId);
        model.addAttribute("category", category);
        model.addAttribute("unread", Boolean.TRUE.equals(unread));
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);
        // Where an action started from, so that it can return to this exact list.
        model.addAttribute("listPath",
                itemsPath(feedId, category, Boolean.TRUE.equals(unread), safePage, snapshot));
        model.addAttribute("firstIndex", articles.isEmpty() ? 0 : (long) (safePage - 1) * pageSize + 1);
        model.addAttribute("lastIndex", (long) (safePage - 1) * pageSize + articles.size());
        return "items";
    }

    /**
     * The filter bar browses categories first and only opens up the feeds of the
     * category that is being read, because a list of every feed is both longer than
     * the screen is wide and rarely what is wanted.
     *
     * <p>Both rows are rendered whole; a row too long for the screen is turned a page
     * at a time in the browser, where the buttons can actually be measured.
     */
    private void addFilterBar(Model model, List<Feed> feeds, Long feedId, String category,
                              boolean unread) {
        String activeCategory = category != null && !category.isBlank() ? category.trim() : null;
        if (activeCategory == null && feedId != null) {
            activeCategory = feeds.stream()
                    .filter(feed -> feedId.equals(feed.id()))
                    .map(Feed::categoryName)
                    .findFirst().orElse(null);
        }

        List<FilterChip> categoryChips = new ArrayList<>();
        categoryChips.add(new FilterChip("All", filterLink(null, null, unread),
                feedId == null && activeCategory == null));
        categoryChips.add(new FilterChip("Unread", filterLink(feedId, category, !unread), unread));
        for (String name : feeds.stream().map(Feed::categoryName).distinct().sorted(CATEGORY_ORDER).toList()) {
            categoryChips.add(new FilterChip(name, filterLink(null, name, unread), name.equals(activeCategory)));
        }

        List<FilterChip> feedChips = new ArrayList<>();
        String openCategory = activeCategory;
        if (openCategory != null) {
            for (Feed feed : feeds) {
                if (openCategory.equals(feed.categoryName())) {
                    feedChips.add(new FilterChip(chipLabel(feed.title()),
                            filterLink(feed.id(), null, unread),
                            feed.id() != null && feed.id().equals(feedId)));
                }
            }
        }

        model.addAttribute("categoryChips", categoryChips);
        model.addAttribute("feedChips", feedChips);
        model.addAttribute("filterLabel", feedId != null
                ? feeds.stream().filter(feed -> feedId.equals(feed.id())).map(Feed::title).findFirst().orElse(null)
                : activeCategory);
    }

    /**
     * A filter button starts its list fresh: at the first page, and for an unread list
     * without the snapshot of the list left behind, which belongs to other articles.
     */
    private static String filterLink(Long feedId, String category, boolean unread) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "feed", feedId == null ? null : String.valueOf(feedId));
        appendParam(query, "category", category);
        appendParam(query, "unread", unread ? "true" : null);
        return query.isEmpty() ? "/items" : "/items?" + query;
    }

    private static void appendParam(StringBuilder query, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** Feed titles are names, not sentences: enough of one to recognise it is enough. */
    private static String chipLabel(String title) {
        String value = title == null || title.isBlank() ? "Untitled" : title.trim();
        return value.length() <= FILTER_LABEL_MAX
                ? value
                : value.substring(0, FILTER_LABEL_MAX - 1).trim() + "…";
    }

    /** One button of the filter bar: the whole list, a category, or a single feed. */
    public record FilterChip(String label, String href, boolean active) {}

    /**
     * Marks the articles of the current list page read and moves on, so a list can be
     * worked through by paging instead of marking every article by hand.
     *
     * <p>An unread list shrinks by exactly the articles that were just marked, which
     * shifts the following ones into the page that was posted from — so that page,
     * not the next one, holds what comes next.
     */
    @PostMapping("/items/advance")
    public String advance(@RequestParam(value = "feed", required = false) Long feedId,
                          @RequestParam(value = "category", required = false) String category,
                          @RequestParam(value = "unread", required = false) Boolean unread,
                          @RequestParam(value = "snapshot", required = false) Long snapshot,
                          @RequestParam(value = "page", defaultValue = "1") int page,
                          @RequestParam(value = "id", required = false) List<Long> ids,
                          RedirectAttributes redirectAttributes) {
        int marked = ids == null || ids.isEmpty() ? 0
                : articleService.markRead(currentUser.requireId(), ids, true);
        redirectAttributes.addFlashAttribute("message", marked == 0
                ? "Nothing left to mark as read"
                : marked == 1 ? "1 article marked as read" : marked + " articles marked as read");

        boolean unreadOnly = Boolean.TRUE.equals(unread);
        int current = Math.max(page, 1);
        return "redirect:" + itemsPath(
                feedId, category, unreadOnly, unreadOnly ? current : current + 1, snapshot) + "#start";
    }

    static String itemsPath(Long feedId, boolean unread, int page) {
        return itemsPath(feedId, null, unread, page, null);
    }

    static String itemsPath(Long feedId, String category, boolean unread, int page, Long snapshot) {
        StringBuilder path = new StringBuilder("/items?page=").append(Math.max(page, 1));
        if (feedId != null) {
            path.append("&feed=").append(feedId);
        }
        if (category != null && !category.isBlank()) {
            path.append("&category=").append(URLEncoder.encode(category, StandardCharsets.UTF_8));
        }
        if (unread) {
            path.append("&unread=true");
            if (snapshot != null) {
                path.append("&snapshot=").append(snapshot);
            }
        }
        return path.toString();
    }

    @GetMapping("/articles/{id}")
    public String article(@PathVariable("id") long id,
                          @RequestParam(value = "images", defaultValue = "false") boolean images,
                          Model model) {
        long userId = currentUser.requireId();
        Article article = articleService.findById(userId, id)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));
        if (!article.read()) {
            articleService.markRead(userId, id, true);
            article = articleService.findById(userId, id).orElse(article);
        }
        String contentHtml = articleService.getContentHtml(article, images);
        model.addAttribute("article", article);
        model.addAttribute("contentHtml", contentHtml);
        model.addAttribute("images", images);
        model.addAttribute("originalUrl", safeHttpUrl(article.url()));
        model.addAttribute("commentsUrl",
                articleService.findCommentsUrl(article).map(AppController::safeHttpUrl).orElse(null));
        return "article";
    }

    @PostMapping("/articles/{id}/read")
    public String markRead(@PathVariable("id") long id,
                           @RequestParam(value = "read", defaultValue = "true") boolean read,
                           @RequestParam(value = "redirect", defaultValue = "/items") String redirect,
                           RedirectAttributes redirectAttributes) {
        try {
            articleService.markRead(currentUser.requireId(), id, read);
            redirectAttributes.addFlashAttribute("message", read ? "Marked as read" : "Marked as unread");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/items";
        }
        return "redirect:" + safeRedirect(redirect);
    }

    /**
     * Sending goes back to where it was started: to the article when it was being
     * read, and to the list when it was picked out of the list, which would
     * otherwise open an article nobody asked to read.
     */
    @PostMapping("/articles/{id}/send")
    public String send(@PathVariable("id") long id,
                       @RequestParam(value = "images", defaultValue = "false") boolean images,
                       @RequestParam(value = "redirect", required = false) String redirect,
                       RedirectAttributes redirectAttributes) {
        String target = redirect == null || redirect.isBlank()
                ? "/articles/" + id + (images ? "?images=true" : "")
                : safeRedirect(redirect);
        try {
            kindleMailService.sendToKindle(currentUser.requireId(), id, images);
            redirectAttributes.addFlashAttribute("message", "Sent to Kindle");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/items";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + target;
    }

    @PostMapping("/articles/{id}/send-async")
    public ResponseEntity<Map<String, String>> sendAsync(
            @PathVariable("id") long id,
            @RequestParam(value = "images", defaultValue = "false") boolean images) {
        try {
            kindleMailService.sendToKindle(currentUser.requireId(), id, images);
            return ResponseEntity.ok(Map.of("message", "Sent to Kindle"));
        } catch (ArticleService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() == null ? "Could not send article" : e.getMessage()));
        }
    }

    /**
     * Prevent open redirects: only allow relative in-app paths.
     */
    static String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return "/items";
        }
        String value = redirect.trim();
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("://")) {
            return "/items";
        }
        return value;
    }

    static String safeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return trimmed;
        }
        return null;
    }
}
