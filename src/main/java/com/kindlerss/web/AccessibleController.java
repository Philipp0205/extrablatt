package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.DisplayPreferences;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ArticleHighlights;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.DisplayPreferencesService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.ReadableTime;
import com.kindlerss.service.TopicCatalog;
import com.kindlerss.service.TopicService;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The accessibility edition: topics instead of URLs, key points before full text,
 * bookmarks, and settings that decide whether the page can be read at all.
 *
 * <p>It answers on its own paths so that both editions can run side by side in one
 * process, and so that this one can be reached (and tested) on any host through
 * {@code ?display=accessible}. Everything below is an ordinary form post that
 * works without JavaScript; the script that ships with these pages only adds
 * read-aloud and instant feedback on top.
 */
@Controller
public class AccessibleController {

    /** Rows here are several lines tall at the default type size; a shorter page is a kinder one. */
    private static final int PAGE_SIZE = 20;

    private final FeedService feedService;
    private final ArticleService articleService;
    private final TopicCatalog topicCatalog;
    private final TopicService topicService;
    private final ArticleHighlights highlights;
    private final DisplayPreferencesService preferencesService;
    private final UserService userService;
    private final ReadableTime readableTime;
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public AccessibleController(FeedService feedService,
                                ArticleService articleService,
                                TopicCatalog topicCatalog,
                                TopicService topicService,
                                ArticleHighlights highlights,
                                DisplayPreferencesService preferencesService,
                                UserService userService,
                                ReadableTime readableTime,
                                CurrentUser currentUser,
                                AppProperties properties) {
        this.feedService = feedService;
        this.articleService = articleService;
        this.topicCatalog = topicCatalog;
        this.topicService = topicService;
        this.highlights = highlights;
        this.preferencesService = preferencesService;
        this.userService = userService;
        this.readableTime = readableTime;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /** Available to every page of this edition, for "3 hours ago" instead of a timestamp. */
    @ModelAttribute("when")
    public ReadableTime when() {
        return readableTime;
    }

    /** One followed subject on the home page. */
    public record TopicSummary(String name, long unread, int sources) {}

    // ---------------------------------------------------------------- topics

    @GetMapping("/topics")
    public String topics(Model model) {
        long userId = currentUser.requireId();
        List<Feed> feeds = feedService.listFeeds(userId);

        Map<String, List<Feed>> byTopic = new LinkedHashMap<>();
        for (Feed feed : feeds) {
            byTopic.computeIfAbsent(feed.categoryName(), ignored -> new ArrayList<>()).add(feed);
        }
        List<TopicSummary> followed = byTopic.entrySet().stream()
                .map(entry -> new TopicSummary(entry.getKey(),
                        entry.getValue().stream().mapToLong(Feed::unreadCount).sum(),
                        entry.getValue().size()))
                .sorted(Comparator.comparing(TopicSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("followedTopics", followed);
        model.addAttribute("totalUnread", feeds.stream().mapToLong(Feed::unreadCount).sum());
        model.addAttribute("savedCount", articleService.countSaved(userId));
        model.addAttribute("hasSources", !feeds.isEmpty());
        model.addAttribute("suggestions", unfollowedTopics(feeds));
        return "accessible/topics";
    }

    @GetMapping("/topics/browse")
    public String browseTopics(Model model) {
        List<Feed> feeds = feedService.listFeeds(currentUser.requireId());
        List<String> followedNames = feeds.stream().map(Feed::categoryName).distinct().toList();
        model.addAttribute("catalog", topicCatalog.topics());
        model.addAttribute("followedNames", followedNames);
        return "accessible/browse-topics";
    }

    @PostMapping("/topics/follow")
    public String followTopic(@RequestParam("topic") String topicKey,
                              RedirectAttributes redirectAttributes) {
        try {
            TopicService.SubscribeResult result = topicService.subscribe(currentUser.requireId(), topicKey);
            redirectAttributes.addFlashAttribute(result.addedAnything() ? "message" : "error",
                    result.summary());
            if (!result.problems().isEmpty()) {
                redirectAttributes.addFlashAttribute("note",
                        "These could not be reached and were skipped: "
                                + String.join(", ", result.problems())
                                + ". You can try this topic again later.");
            }
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/topics";
    }

    /** Catalogue topics the account does not already follow, for the empty state and for "add more". */
    private List<TopicCatalog.Topic> unfollowedTopics(List<Feed> feeds) {
        List<String> followed = feeds.stream().map(Feed::categoryName).map(String::toLowerCase).toList();
        return topicCatalog.topics().stream()
                .filter(topic -> !followed.contains(topic.name().toLowerCase()))
                .toList();
    }

    // --------------------------------------------------------------- sources

    @GetMapping("/sources")
    public String sources(Model model) {
        long userId = currentUser.requireId();
        List<Feed> feeds = feedService.listFeeds(userId);
        Map<String, List<Feed>> byTopic = new LinkedHashMap<>();
        for (Feed feed : feeds) {
            byTopic.computeIfAbsent(feed.categoryName(), ignored -> new ArrayList<>()).add(feed);
        }
        model.addAttribute("sourcesByTopic", byTopic);
        model.addAttribute("topicNames", feeds.stream()
                .map(Feed::categoryName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        return "accessible/sources";
    }

    @PostMapping("/sources/add")
    public String addSource(@RequestParam("address") String address,
                            @RequestParam(value = "topic", required = false) String topic,
                            @RequestParam(value = "newTopic", required = false) String newTopic,
                            RedirectAttributes redirectAttributes) {
        try {
            Feed feed = topicService.addWebsite(currentUser.requireId(), address,
                    AppController.resolveCategory(topic, newTopic));
            redirectAttributes.addFlashAttribute("message", "Now following " + feed.title() + ".");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage() == null
                    ? "That website could not be added." : e.getMessage());
        }
        return "redirect:/sources";
    }

    @PostMapping("/sources/{id}/remove")
    public String removeSource(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        if (feedService.deleteFeed(currentUser.requireId(), id)) {
            redirectAttributes.addFlashAttribute("message", "That source has been removed.");
        } else {
            redirectAttributes.addFlashAttribute("error", "That source is no longer there.");
        }
        return "redirect:/sources";
    }

    // ---------------------------------------------------------------- articles

    /**
     * An article list, by topic or by single source.
     *
     * <p>Reading an article marks it read, which would otherwise make it disappear
     * from underneath the list it was opened from — disorienting at any type size,
     * worse at this one. So a "new articles" list is pinned to the moment it was
     * opened: anything read since then keeps its place until the list is asked for
     * again.
     */
    @GetMapping("/list")
    public String list(@RequestParam(value = "topic", required = false) String topic,
                       @RequestParam(value = "source", required = false) Long sourceId,
                       @RequestParam(value = "show", required = false) String show,
                       @RequestParam(value = "since", required = false) Long since,
                       @RequestParam(value = "page", defaultValue = "1") int page,
                       Model model) {
        long userId = currentUser.requireId();
        if (sourceId != null && feedService.findById(userId, sourceId).isEmpty()) {
            throw new ArticleService.NotFoundException("That source is no longer there");
        }
        boolean onlyNew = !"all".equalsIgnoreCase(show);
        if (onlyNew && since == null) {
            return "redirect:" + listPath(topic, sourceId, true, Math.max(page, 1),
                    System.currentTimeMillis());
        }
        Instant snapshot = onlyNew ? Instant.ofEpochMilli(Math.min(since, System.currentTimeMillis())) : null;
        Boolean unreadOnly = onlyNew ? Boolean.TRUE : null;

        long total = articleService.count(userId, sourceId, topic, unreadOnly, snapshot);
        int totalPages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.min(Math.max(page, 1), totalPages);
        List<Article> articles =
                articleService.findPage(userId, sourceId, topic, unreadOnly, snapshot, safePage, PAGE_SIZE);

        model.addAttribute("articles", articles);
        model.addAttribute("heading", listHeading(userId, topic, sourceId));
        model.addAttribute("topic", topic);
        model.addAttribute("sourceId", sourceId);
        model.addAttribute("onlyNew", onlyNew);
        model.addAttribute("since", since);
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);
        model.addAttribute("listPath", listPath(topic, sourceId, onlyNew, safePage, since));
        model.addAttribute("otherViewPath", listPath(topic, sourceId, !onlyNew, 1, null));
        model.addAttribute("nextPath", safePage < totalPages
                ? listPath(topic, sourceId, onlyNew, safePage + 1, since) : null);
        model.addAttribute("previousPath", safePage > 1
                ? listPath(topic, sourceId, onlyNew, safePage - 1, since) : null);
        return "accessible/list";
    }

    @GetMapping("/saved")
    public String saved(@RequestParam(value = "page", defaultValue = "1") int page, Model model) {
        long userId = currentUser.requireId();
        long total = articleService.countSaved(userId);
        int totalPages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.min(Math.max(page, 1), totalPages);
        model.addAttribute("articles", articleService.findSavedPage(userId, safePage, PAGE_SIZE));
        model.addAttribute("total", total);
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("listPath", "/saved?page=" + safePage);
        model.addAttribute("nextPath", safePage < totalPages ? "/saved?page=" + (safePage + 1) : null);
        model.addAttribute("previousPath", safePage > 1 ? "/saved?page=" + (safePage - 1) : null);
        return "accessible/saved";
    }

    @GetMapping("/read/{id}")
    public String read(@PathVariable("id") long id,
                       @RequestParam(value = "images", defaultValue = "false") boolean images,
                       @RequestParam(value = "from", required = false) String from,
                       HttpServletRequest request,
                       Model model) {
        long userId = currentUser.requireId();
        Article article = articleService.findById(userId, id)
                .orElseThrow(() -> new ArticleService.NotFoundException("That article is no longer there"));
        if (!article.read()) {
            articleService.markRead(userId, id, true);
            article = articleService.findById(userId, id).orElse(article);
        }
        String contentHtml = articleService.getContentHtml(article, images);
        ArticleHighlights.Summary summary = highlights.summarize(contentHtml);

        model.addAttribute("article", article);
        model.addAttribute("contentHtml", contentHtml);
        model.addAttribute("summary", summary);
        model.addAttribute("showKeyPoints",
                preferencesService.forRequest(request).keyPointsFirst() && summary.hasPoints());
        model.addAttribute("images", images);
        model.addAttribute("originalUrl", AppController.safeHttpUrl(article.url()));
        model.addAttribute("backPath", AppController.safeRedirect(from == null ? "/list" : from));
        model.addAttribute("selfPath", "/read/" + id + (images ? "?images=true" : ""));
        model.addAttribute("kindleConfigured", hasKindleAddress(userId));
        return "accessible/article";
    }

    /**
     * Bookmarks an article. Lives on the shared {@code /articles} path rather than
     * inside this edition's own, because a saved article is account data, not a
     * property of the page it was saved from.
     */
    @PostMapping("/articles/{id}/save")
    public String save(@PathVariable("id") long id,
                       @RequestParam(value = "saved", defaultValue = "true") boolean saved,
                       @RequestParam(value = "redirect", required = false) String redirect,
                       RedirectAttributes redirectAttributes) {
        try {
            articleService.setSaved(currentUser.requireId(), id, saved);
            redirectAttributes.addFlashAttribute("message",
                    saved ? "Saved. You can find it under Saved articles." : "Removed from saved articles.");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + AppController.safeRedirect(redirect == null ? "/saved" : redirect);
    }

    /**
     * The same bookmark, without losing the reader's place. A full page reload
     * throws a screen reader back to the top of the document and a magnified view
     * back to its first corner, which is a heavy price for pressing "Save".
     */
    @PostMapping("/articles/{id}/save-async")
    public ResponseEntity<Map<String, Object>> saveAsync(
            @PathVariable("id") long id,
            @RequestParam(value = "saved", defaultValue = "true") boolean saved) {
        try {
            articleService.setSaved(currentUser.requireId(), id, saved);
            return ResponseEntity.ok(Map.of(
                    "saved", saved,
                    "message", saved ? "Saved to your saved articles" : "Removed from your saved articles"));
        } catch (ArticleService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "That article is no longer there"));
        }
    }

    @PostMapping("/read/{id}/status")
    public String status(@PathVariable("id") long id,
                         @RequestParam(value = "read", defaultValue = "false") boolean read,
                         @RequestParam(value = "redirect", required = false) String redirect,
                         RedirectAttributes redirectAttributes) {
        try {
            articleService.markRead(currentUser.requireId(), id, read);
            redirectAttributes.addFlashAttribute("message",
                    read ? "Marked as read." : "Marked as new again.");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + AppController.safeRedirect(redirect == null ? "/list" : redirect);
    }

    // ---------------------------------------------------------------- display

    @GetMapping("/display")
    public String display(HttpServletRequest request, Model model) {
        model.addAttribute("preferences", preferencesService.forRequest(request));
        model.addAttribute("themes", DisplayPreferences.Theme.values());
        model.addAttribute("fonts", DisplayPreferences.Font.values());
        return "accessible/display";
    }

    @PostMapping("/display")
    public String saveDisplay(@RequestParam(value = "theme", required = false) String theme,
                              @RequestParam(value = "textSize", defaultValue = "3") int textSize,
                              @RequestParam(value = "lineSpacing", defaultValue = "2") int lineSpacing,
                              @RequestParam(value = "font", required = false) String font,
                              @RequestParam(value = "wideLetterSpacing", defaultValue = "false") boolean wide,
                              @RequestParam(value = "keyPointsFirst", defaultValue = "false") boolean keyPoints,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              RedirectAttributes redirectAttributes) {
        DisplayPreferences preferences = new DisplayPreferences(
                DisplayPreferences.Theme.parse(theme, DisplayPreferences.DEFAULTS.theme()),
                textSize, lineSpacing,
                DisplayPreferences.Font.parse(font, DisplayPreferences.DEFAULTS.font()),
                wide, keyPoints);
        preferencesService.save(request, response, preferences);
        redirectAttributes.addFlashAttribute("message", "Your display settings have been saved.");
        return "redirect:/display";
    }

    /**
     * The one-tap larger/smaller control that sits in the header of every page, so
     * text size can be fixed where it is being read rather than on a settings page
     * that first has to be found and read.
     */
    @PostMapping("/display/size")
    public String changeSize(@RequestParam("step") String step,
                             @RequestParam(value = "redirect", required = false) String redirect,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        DisplayPreferences current = preferencesService.forRequest(request);
        int change = "smaller".equalsIgnoreCase(step) ? -1 : 1;
        preferencesService.save(request, response, current.withTextSize(current.textSize() + change));
        return "redirect:" + AppController.safeRedirect(redirect == null ? "/topics" : redirect);
    }

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("newslettersEnabled", properties.newsletters().enabled());
        return "accessible/help";
    }

    // ---------------------------------------------------------------- helpers

    private boolean hasKindleAddress(long userId) {
        return userService.findById(userId)
                .map(user -> user.kindleEmail() != null && !user.kindleEmail().isBlank())
                .orElse(false);
    }

    private String listHeading(long userId, String topic, Long sourceId) {
        if (sourceId != null) {
            return feedService.findById(userId, sourceId).map(Feed::title).orElse("Articles");
        }
        return topic == null || topic.isBlank() ? "All articles" : topic;
    }

    static String listPath(String topic, Long sourceId, boolean onlyNew, int page, Long since) {
        StringBuilder path = new StringBuilder("/list?page=").append(Math.max(page, 1));
        if (topic != null && !topic.isBlank()) {
            path.append("&topic=").append(URLEncoder.encode(topic, StandardCharsets.UTF_8));
        }
        if (sourceId != null) {
            path.append("&source=").append(sourceId);
        }
        path.append("&show=").append(onlyNew ? "new" : "all");
        if (onlyNew && since != null) {
            path.append("&since=").append(since);
        }
        return path.toString();
    }
}
