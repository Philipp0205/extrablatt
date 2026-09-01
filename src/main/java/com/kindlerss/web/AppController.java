package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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

    /**
     * Leaves an open category and puts the filter row back on the categories. The view
     * draws the arrow, because the narrowest screens keep the arrow and drop the word.
     */
    private static final String BACK_LABEL = "All";

    /** Feeds that were never put in a category are browsed last. */
    private static final Comparator<String> CATEGORY_ORDER =
            Comparator.comparing((String name) -> Feed.UNCATEGORIZED.equals(name))
                    .thenComparing(Comparator.<String>naturalOrder());

    private final FeedService feedService;
    private final ArticleService articleService;
    private final KindleMailService kindleMailService;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final EntitlementService entitlementService;
    private final AppProperties properties;
    private final int pageSize;
    private final String mailFrom;

    public AppController(FeedService feedService,
                         ArticleService articleService,
                         KindleMailService kindleMailService,
                         UserService userService,
                         CurrentUser currentUser,
                         EntitlementService entitlementService,
                         AppProperties properties) {
        this.feedService = feedService;
        this.articleService = articleService;
        this.kindleMailService = kindleMailService;
        this.userService = userService;
        this.currentUser = currentUser;
        this.entitlementService = entitlementService;
        this.properties = properties;
        this.pageSize = properties.articles().pageSize();
        this.mailFrom = properties.mailFrom();
    }

    @GetMapping("/")
    public String home(@RequestParam(value = "view", defaultValue = "feeds") String view,
                       @RequestParam(value = "category", required = false) String category,
                       Model model) {
        long userId = currentUser.requireId();
        feedService.refreshForUserSoon(userId);
        List<Feed> feeds = feedService.listFeeds(userId);
        long totalUnread = feeds.stream().mapToLong(Feed::unreadCount).sum();
        model.addAttribute("feeds", feeds);
        Map<String, List<Feed>> feedGroups = new LinkedHashMap<>();
        for (Feed feed : feeds) {
            feedGroups.computeIfAbsent(feed.categoryName(), ignored -> new ArrayList<>()).add(feed);
        }
        List<FeedCategorySummary> feedCategories = feedGroups.entrySet().stream()
                .map(entry -> new FeedCategorySummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().mapToLong(Feed::unreadCount).sum()))
                .sorted(Comparator.comparing(FeedCategorySummary::name, CATEGORY_ORDER))
                .toList();
        model.addAttribute("feedCategories", feedCategories);
        List<String> categories = existingCategories(feeds);
        model.addAttribute("categories", categories);
        model.addAttribute("defaultFeeds", feedService.defaultFeeds(userId));
        model.addAttribute("totalUnread", totalUnread);
        String selectedCategory = category == null ? null : category.trim();
        if (selectedCategory != null && !categories.contains(selectedCategory)
                && !Feed.UNCATEGORIZED.equals(selectedCategory)) {
            selectedCategory = null;
        }
        String activeView = selectedCategory != null ? "category"
                : switch (view) {
                    case "add" -> view;
                    default -> "feeds";
                };
        model.addAttribute("activeView", activeView);
        model.addAttribute("selectedCategory", selectedCategory);
        List<Feed> selectedFeeds = selectedCategory == null
                ? List.of()
                : feedGroups.getOrDefault(selectedCategory, List.of());
        model.addAttribute("selectedFeeds", selectedFeeds);
        model.addAttribute("selectedUnread",
                selectedFeeds.stream().mapToLong(Feed::unreadCount).sum());
        model.addAttribute("kindleConfigured", isKindleConfigured(userId));
        model.addAttribute("mailFrom", mailFrom);
        var user = userService.findById(userId).orElse(null);
        var entitlement = entitlementService.forUser(userId);
        boolean newslettersEnabled = properties.newsletters().enabled()
                && (entitlement.newsletters() || (user != null && user.newsletterInboundToken() != null));
        model.addAttribute("newslettersEnabled", newslettersEnabled);
        if (newslettersEnabled && user != null) {
            String token = userService.ensureNewsletterInboundToken(userId);
            model.addAttribute("newsletterAddress", token + "@" + properties.newsletters().inboundDomain());
        }
        return "index";
    }

    record FeedCategorySummary(String name, int feedCount, long unreadCount) {
    }

    /** The distinct categories already in use, so they can fill a category drop-down. */
    private static List<String> existingCategories(List<Feed> feeds) {
        return feeds.stream()
                .map(Feed::category)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean isKindleConfigured(long userId) {
        return userService.findById(userId)
                .map(user -> user.kindleEmail() != null && !user.kindleEmail().isBlank())
                .orElse(false);
    }

    @PostMapping("/feeds")
    public String addFeed(@RequestParam("url") String url,
                          @RequestParam(value = "category", required = false) String category,
                          @RequestParam(value = "newCategory", required = false) String newCategory,
                          RedirectAttributes redirectAttributes) {
        try {
            Feed feed = feedService.addFeed(currentUser.requireId(), url, resolveCategory(category, newCategory));
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

    /**
     * Fetches a pasted page, stores it as an article, and emails the EPUB. The
     * article is kept even when sending fails, so a Kindle address that is not
     * set yet can be filled in and the send retried from the article page.
     */
    @PostMapping("/articles/from-url")
    public String sendFromUrl(@RequestParam("url") String url,
                              @RequestParam(value = "images", defaultValue = "false") boolean images,
                              RedirectAttributes redirectAttributes) {
        Article article;
        try {
            article = articleService.importFromUrl(currentUser.requireId(), url);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error",
                    e.getMessage() == null ? "That page could not be sent" : e.getMessage());
            return "redirect:/";
        }
        String successTarget = "/articles/" + article.id() + (images ? "?images=true" : "");
        try {
            kindleMailService.sendToKindle(currentUser.requireId(), article.id(), images);
            redirectAttributes.addFlashAttribute("message", "Sent to Kindle");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + successTarget;
    }

    @PostMapping("/feeds/{id}/category")
    public String categorizeFeed(@PathVariable("id") long id,
                                 @RequestParam(value = "category", required = false) String category,
                                 @RequestParam(value = "newCategory", required = false) String newCategory,
                                 @RequestParam(value = "redirect", defaultValue = "/") String redirect,
                                 RedirectAttributes redirectAttributes) {
        if (feedService.categorizeFeed(currentUser.requireId(), id, resolveCategory(category, newCategory))) {
            redirectAttributes.addFlashAttribute("message", "Feed category updated");
        } else {
            redirectAttributes.addFlashAttribute("error", "Feed not found");
        }
        return "redirect:" + safeRedirect(redirect);
    }

    /**
     * The category comes from a drop-down of the categories already in use, plus a
     * "New category" choice that reveals a text field. A typed new name wins; the
     * sentinel value and the blank "Uncategorized" choice both mean no category.
     */
    static final String NEW_CATEGORY = "__new__";

    static String resolveCategory(String category, String newCategory) {
        if (newCategory != null && !newCategory.isBlank()) {
            return newCategory.trim();
        }
        if (category == null || category.isBlank() || NEW_CATEGORY.equals(category.trim())) {
            return null;
        }
        return category.trim();
    }

    @PostMapping("/categories/rename")
    public String renameCategory(@RequestParam("oldCategory") String oldCategory,
                                 @RequestParam("newCategory") String newCategory,
                                 @RequestParam(value = "redirect", defaultValue = "/") String redirect,
                                 RedirectAttributes redirectAttributes) {
        try {
            int updated = feedService.renameCategory(currentUser.requireId(), oldCategory, newCategory);
            redirectAttributes.addFlashAttribute("message", renameResult(updated));
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + safeRedirect(redirect);
    }

    /** What a rename did, told from the number of feeds it moved. */
    static String renameResult(int updatedFeeds) {
        if (updatedFeeds == FeedService.CATEGORY_NAME_UNCHANGED) {
            return "That is already the name of this category";
        }
        if (updatedFeeds == 0) {
            return "No feeds found in that category";
        }
        return "Renamed category for " + updatedFeeds + (updatedFeeds == 1 ? " feed" : " feeds");
    }

    @PostMapping("/feeds/{id}/read")
    public String markFeedRead(@PathVariable("id") long id,
                               @RequestParam(value = "redirect", defaultValue = "/") String redirect,
                               RedirectAttributes redirectAttributes) {
        try {
            int marked = articleService.markFeedRead(currentUser.requireId(), id);
            redirectAttributes.addFlashAttribute("message", marked == 0
                    ? "Nothing left to mark as read"
                    : marked == 1 ? "1 article marked as read" : marked + " articles marked as read");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + safeRedirect(redirect);
    }

    @PostMapping("/feeds/{id}/delete")
    public String deleteFeed(@PathVariable("id") long id,
                             @RequestParam(value = "redirect", defaultValue = "/") String redirect,
                             RedirectAttributes redirectAttributes) {
        if (!feedService.deleteFeed(currentUser.requireId(), id)) {
            redirectAttributes.addFlashAttribute("error", "Feed not found");
        } else {
            redirectAttributes.addFlashAttribute("message", "Feed deleted");
        }
        return "redirect:" + safeRedirect(redirect);
    }

    @PostMapping("/refresh")
    public String refresh(@RequestParam(value = "redirect", defaultValue = "/") String redirect,
                          RedirectAttributes redirectAttributes) {
        try {
            feedService.refreshForUser(currentUser.requireId());
            redirectAttributes.addFlashAttribute("message", "Feeds refreshed");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Refresh failed: " + e.getMessage());
        }
        return "redirect:" + safeRedirect(redirect);
    }

    @GetMapping("/items")
    public String items(@RequestParam(value = "feed", required = false) Long feedId,
                        @RequestParam(value = "category", required = false) String category,
                        @RequestParam(value = "unread", required = false) Boolean unread,
                        @RequestParam(value = "snapshot", required = false) Long snapshot,
                        @RequestParam(value = "page", defaultValue = "1") int page,
                        Model model) {
        long userId = currentUser.requireId();
        feedService.refreshForUserSoon(userId);
        if (feedId != null && feedService.findById(userId, feedId).isEmpty()) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
        boolean unreadByDefault = unread == null || Boolean.TRUE.equals(unread);
        Boolean unreadOnly = unreadByDefault ? Boolean.TRUE : null;
        if (unreadByDefault && snapshot == null) {
            return "redirect:" + itemsPath(feedId, category, true, Math.max(page, 1),
                    System.currentTimeMillis());
        }
        Instant unreadSnapshot = unreadByDefault && snapshot != null
                ? Instant.ofEpochMilli(Math.min(snapshot, System.currentTimeMillis())) : null;
        boolean markReadOnNextPage = userService.markReadOnNextPage(userId);
        long total = category == null && unreadSnapshot == null
                ? articleService.count(userId, feedId, unreadOnly)
                : articleService.count(userId, feedId, category, unreadOnly, unreadSnapshot);
        int totalPages = totalPages(total);
        // A page number can point past the end — a link kept from a list that has
        // since lost articles; show the last page rather than an empty one.
        int safePage = Math.min(Math.max(page, 1), totalPages);
        List<Article> articles = category == null && unreadSnapshot == null
                ? articleService.findPage(userId, feedId, unreadOnly, safePage, pageSize)
                : articleService.findPage(userId, feedId, category, unreadOnly, unreadSnapshot, safePage, pageSize);

        model.addAttribute("articles", articles);
        addFilterBar(model, feedService.listFeeds(userId), feedId, category, unreadByDefault);
        model.addAttribute("feedId", feedId);
        model.addAttribute("category", category);
        model.addAttribute("unread", unreadByDefault);
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);
        // Where an action started from, so that it can return to this exact list.
        model.addAttribute("listPath",
                itemsPath(feedId, category, unreadByDefault, safePage, snapshot));
        model.addAttribute("firstIndex", articles.isEmpty() ? 0 : (long) (safePage - 1) * pageSize + 1);
        model.addAttribute("lastIndex", (long) (safePage - 1) * pageSize + articles.size());
        model.addAttribute("markReadOnNextPage", markReadOnNextPage);
        model.addAttribute("forwardLabel", articles.isEmpty()
                ? null : forwardLabel(markReadOnNextPage, safePage < totalPages));
        // Counted without the snapshot: the snapshot deliberately holds on to the
        // articles this sitting has already read, and what is left to read is the
        // one number the reader cannot work out from the list in front of them.
        long unreadLeft = articleService.count(userId, feedId, category, Boolean.TRUE, null);
        model.addAttribute("unreadLeft", unreadLeft);
        model.addAttribute("readThroughPercent", readThroughPercent(total, unreadLeft));
        return "items";
    }

    /** How much of the list on screen is behind the reader, as a whole percentage. */
    static int readThroughPercent(long total, long unreadLeft) {
        if (total <= 0) {
            return 0;
        }
        long done = Math.max(0, Math.min(total, total - unreadLeft));
        return (int) (done * 100 / total);
    }

    /**
     * What leaving the loaded page does, in the reader's own words.
     *
     * <p>One label for both the pager's last page and the button that stands in for it
     * without the reader script, because it is one action: it can mark the articles
     * that were paged past read, and it fetches the next batch when the list has one.
     * Saying both is what makes the last page of a batch worth pressing — the reader
     * would otherwise have to guess whether "Mark read" also loads what follows.
     */
    static String forwardLabel(boolean marksRead, boolean hasMore) {
        if (marksRead) {
            return hasMore ? "Mark read and load more" : "Mark read and continue";
        }
        return hasMore ? "Load more articles" : "Next articles";
    }

    /**
     * The filter bar is one row on either level: the categories, or — once one of them
     * is open — the feeds inside it. A second row costs a list that is read a screen
     * at a time two lines of every page, and a row holding every feed of every
     * category is longer than the screen is wide anyway.
     *
     * <p>The row holds three kinds of thing, and the view draws each differently
     * because they answer different questions. {@code filterChips} is the level
     * itself — where the reader is — rendered whole and clipped to one line in the
     * browser, where the buttons can actually be measured. {@code backChip} leaves
     * the level, and {@code modeChip} turns unread-only on and off; both stay outside
     * that clipping, so turning the row cannot carry them off the screen.
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

        FilterChip backChip = null;
        FilterChip modeChip;
        List<FilterChip> filterChips = new ArrayList<>();
        if (activeCategory == null) {
            modeChip = new FilterChip("Unread", filterLink(null, null, !unread), unread);
            filterChips.add(new FilterChip("All", filterLink(null, null, unread), true));
            for (String name : feeds.stream().map(Feed::categoryName).distinct().sorted(CATEGORY_ORDER).toList()) {
                filterChips.add(new FilterChip(name, filterLink(null, name, unread), false));
            }
        } else {
            backChip = new FilterChip(BACK_LABEL, filterLink(null, null, unread), false);
            modeChip = new FilterChip("Unread", filterLink(feedId, category, !unread), unread);
            // The open category leads its own feeds: it is the whole of this level, and
            // what the row falls back to when no single feed is chosen.
            filterChips.add(new FilterChip(activeCategory, filterLink(null, activeCategory, unread),
                    feedId == null));
            for (Feed feed : feeds) {
                if (activeCategory.equals(feed.categoryName())) {
                    filterChips.add(new FilterChip(chipLabel(feed.title()),
                            filterLink(feed.id(), null, unread),
                            feed.id() != null && feed.id().equals(feedId)));
                }
            }
        }

        model.addAttribute("backChip", backChip);
        model.addAttribute("modeChip", modeChip);
        model.addAttribute("filterChips", filterChips);
    }

    /**
     * A filter button starts its list fresh: at the first page, and for an unread list
     * without the snapshot of the list left behind, which belongs to other articles.
     */
    private static String filterLink(Long feedId, String category, boolean unread) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "feed", feedId == null ? null : String.valueOf(feedId));
        appendParam(query, "category", category);
        appendParam(query, "unread", String.valueOf(unread));
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
     * Posts the current list page and moves on. When the account marks articles
     * read on the next page, the posted ids are marked first; either way the list
     * then moves forward by one page.
     *
     * <p>An unread list is taken from a snapshot, and keeps the articles that were
     * read after that snapshot was taken — including the ones just marked. It
     * therefore does not shrink under the reader, and moving on means the next
     * page. Reading past its last page means the whole list has been read through:
     * a fresh unread list is opened, which leaves those articles behind and shows
     * only what is still unread.
     */
    @PostMapping("/items/advance")
    public String advance(@RequestParam(value = "feed", required = false) Long feedId,
                          @RequestParam(value = "category", required = false) String category,
                          @RequestParam(value = "unread", required = false) Boolean unread,
                          @RequestParam(value = "snapshot", required = false) Long snapshot,
                          @RequestParam(value = "page", defaultValue = "1") int page,
                          @RequestParam(value = "id", required = false) List<Long> ids,
                          RedirectAttributes redirectAttributes) {
        long userId = currentUser.requireId();
        boolean markReadOnNextPage = userService.markReadOnNextPage(userId);
        int marked = 0;
        if (markReadOnNextPage) {
            marked = ids == null || ids.isEmpty() ? 0
                    : articleService.markRead(userId, ids, true);
            redirectAttributes.addFlashAttribute("message", marked == 0
                    ? "Nothing left to mark as read"
                    : marked == 1 ? "1 article marked as read" : marked + " articles marked as read");
        }

        boolean unreadOnly = Boolean.TRUE.equals(unread);
        boolean readThrough = unreadOnly && markReadOnNextPage;
        int current = Math.max(page, 1);
        // An unread list with no snapshot to hold the articles just marked read is
        // the one list that does shrink, and what comes next moves into the page
        // that was posted from.
        if (readThrough && snapshot == null) {
            return "redirect:" + itemsPath(feedId, category, true, current, null) + "#start";
        }
        int next = current + 1;
        if (readThrough && next > unreadPages(userId, feedId, category, snapshot)) {
            return "redirect:" + itemsPath(feedId, category, true, 1, null) + "#start";
        }
        return "redirect:" + itemsPath(feedId, category, unreadOnly, next, snapshot) + "#start";
    }

    /**
     * Marks the articles of one screen read without leaving the list.
     *
     * <p>The reader turns several screens inside a single loaded page, and a screen
     * that has been turned past has been read through just as much as a whole page
     * has. Posting it here keeps that promise while the reader stays put, and
     * answers with what is still unread so the meter under the page can follow.
     */
    @PostMapping("/items/read")
    @ResponseBody
    public Map<String, Object> markScreenRead(@RequestParam(value = "feed", required = false) Long feedId,
                                              @RequestParam(value = "category", required = false) String category,
                                              @RequestParam(value = "id", required = false) List<Long> ids) {
        long userId = currentUser.requireId();
        int marked = userService.markReadOnNextPage(userId) && ids != null && !ids.isEmpty()
                ? articleService.markRead(userId, ids, true)
                : 0;
        return Map.of("marked", marked,
                "unreadLeft", articleService.count(userId, feedId, category, Boolean.TRUE, null));
    }

    /** Pages the unread list of this snapshot holds, so paging can tell where it ends. */
    private int unreadPages(long userId, Long feedId, String category, long snapshot) {
        Instant taken = Instant.ofEpochMilli(Math.min(snapshot, System.currentTimeMillis()));
        return totalPages(articleService.count(userId, feedId, category, Boolean.TRUE, taken));
    }

    private int totalPages(long total) {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
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
        path.append("&unread=").append(unread);
        if (unread) {
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
    public ResponseEntity<Map<String, Object>> sendAsync(
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
