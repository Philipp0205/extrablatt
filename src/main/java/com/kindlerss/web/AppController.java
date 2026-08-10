package com.kindlerss.web;

import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class AppController {

    private static final int PAGE_SIZE = 20;

    private final FeedService feedService;
    private final ArticleService articleService;
    private final KindleMailService kindleMailService;

    public AppController(FeedService feedService,
                         ArticleService articleService,
                         KindleMailService kindleMailService) {
        this.feedService = feedService;
        this.articleService = articleService;
        this.kindleMailService = kindleMailService;
    }

    @GetMapping("/")
    public String home(Model model) {
        List<Feed> feeds = feedService.listFeeds();
        long totalUnread = feeds.stream().mapToLong(Feed::unreadCount).sum();
        model.addAttribute("feeds", feeds);
        model.addAttribute("totalUnread", totalUnread);
        return "index";
    }

    @PostMapping("/feeds")
    public String addFeed(@RequestParam("url") String url, RedirectAttributes redirectAttributes) {
        try {
            Feed feed = feedService.addFeed(url);
            redirectAttributes.addFlashAttribute("message", "Added feed: " + feed.title());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/feeds/{id}/delete")
    public String deleteFeed(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        if (!feedService.deleteFeed(id)) {
            redirectAttributes.addFlashAttribute("error", "Feed not found");
        } else {
            redirectAttributes.addFlashAttribute("message", "Feed deleted");
        }
        return "redirect:/";
    }

    @PostMapping("/refresh")
    public String refresh(RedirectAttributes redirectAttributes) {
        try {
            feedService.refreshAll();
            redirectAttributes.addFlashAttribute("message", "Feeds refreshed");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Refresh failed: " + e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/items")
    public String items(@RequestParam(value = "feed", required = false) Long feedId,
                        @RequestParam(value = "unread", required = false) Boolean unread,
                        @RequestParam(value = "page", defaultValue = "1") int page,
                        Model model) {
        if (feedId != null && feedService.findById(feedId).isEmpty()) {
            throw new ArticleService.NotFoundException("Feed not found");
        }
        Boolean unreadOnly = Boolean.TRUE.equals(unread) ? Boolean.TRUE : null;
        long total = articleService.count(feedId, unreadOnly);
        int totalPages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        // Marking a page read shrinks an unread list, so a page number can end up
        // past the end; show the last page rather than an empty one.
        int safePage = Math.min(Math.max(page, 1), totalPages);
        List<Article> articles = articleService.findPage(feedId, unreadOnly, safePage, PAGE_SIZE);

        model.addAttribute("articles", articles);
        model.addAttribute("feeds", feedService.listFeeds());
        model.addAttribute("feedId", feedId);
        model.addAttribute("unread", Boolean.TRUE.equals(unread));
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("total", total);
        model.addAttribute("firstIndex", articles.isEmpty() ? 0 : (long) (safePage - 1) * PAGE_SIZE + 1);
        model.addAttribute("lastIndex", (long) (safePage - 1) * PAGE_SIZE + articles.size());
        return "items";
    }

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
                          @RequestParam(value = "unread", required = false) Boolean unread,
                          @RequestParam(value = "page", defaultValue = "1") int page,
                          @RequestParam(value = "id", required = false) List<Long> ids,
                          RedirectAttributes redirectAttributes) {
        int marked = ids == null || ids.isEmpty() ? 0 : articleService.markRead(ids, true);
        redirectAttributes.addFlashAttribute("message", marked == 0
                ? "Nothing left to mark as read"
                : marked == 1 ? "1 article marked as read" : marked + " articles marked as read");

        boolean unreadOnly = Boolean.TRUE.equals(unread);
        int current = Math.max(page, 1);
        return "redirect:" + itemsPath(feedId, unreadOnly, unreadOnly ? current : current + 1) + "#start";
    }

    static String itemsPath(Long feedId, boolean unread, int page) {
        StringBuilder path = new StringBuilder("/items?page=").append(Math.max(page, 1));
        if (feedId != null) {
            path.append("&feed=").append(feedId);
        }
        if (unread) {
            path.append("&unread=true");
        }
        return path.toString();
    }

    @GetMapping("/articles/{id}")
    public String article(@PathVariable("id") long id,
                          @RequestParam(value = "images", defaultValue = "false") boolean images,
                          Model model) {
        Article article = articleService.findById(id)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));
        if (!article.read()) {
            articleService.markRead(id, true);
            article = articleService.findById(id).orElse(article);
        }
        String contentHtml = articleService.getContentHtml(article, images);
        model.addAttribute("article", article);
        model.addAttribute("contentHtml", contentHtml);
        model.addAttribute("images", images);
        model.addAttribute("originalUrl", safeHttpUrl(article.url()));
        return "article";
    }

    @PostMapping("/articles/{id}/read")
    public String markRead(@PathVariable("id") long id,
                           @RequestParam(value = "read", defaultValue = "true") boolean read,
                           @RequestParam(value = "redirect", defaultValue = "/items") String redirect,
                           RedirectAttributes redirectAttributes) {
        try {
            articleService.markRead(id, read);
            redirectAttributes.addFlashAttribute("message", read ? "Marked as read" : "Marked as unread");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/items";
        }
        return "redirect:" + safeRedirect(redirect);
    }

    @PostMapping("/articles/{id}/send")
    public String send(@PathVariable("id") long id,
                       @RequestParam(value = "images", defaultValue = "false") boolean images,
                       RedirectAttributes redirectAttributes) {
        try {
            kindleMailService.sendToKindle(id, images);
            redirectAttributes.addFlashAttribute("message", "Sent to Kindle");
        } catch (ArticleService.NotFoundException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/items";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/articles/" + id + (images ? "?images=true" : "");
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
