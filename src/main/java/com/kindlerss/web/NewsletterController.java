package com.kindlerss.web;

import com.kindlerss.domain.Feed;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.FeedService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Newsletter subscriptions: unlike RSS feeds, these are created with just a name
 * and filled later by e-mail (see {@link NewsletterInboundController}). Deleting
 * and re-categorizing reuse the existing {@code /feeds/*} endpoints since a
 * newsletter is stored as a feed.
 */
@Controller
public class NewsletterController {

    private final FeedService feedService;
    private final CurrentUser currentUser;

    public NewsletterController(FeedService feedService, CurrentUser currentUser) {
        this.feedService = feedService;
        this.currentUser = currentUser;
    }

    @PostMapping("/newsletters")
    public String addNewsletter(@RequestParam("title") String title,
                                @RequestParam(value = "category", required = false) String category,
                                @RequestParam(value = "newCategory", required = false) String newCategory,
                                RedirectAttributes redirectAttributes) {
        try {
            Feed feed = feedService.addNewsletter(currentUser.requireId(), title,
                    AppController.resolveCategory(category, newCategory));
            String address = feedService.newsletterAddress(feed);
            redirectAttributes.addFlashAttribute("message",
                    "Added newsletter \"" + feed.title() + "\". Subscribe to it using " + address);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/newsletters/{id}/regenerate")
    public String regenerateAddress(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        try {
            Feed feed = feedService.regenerateNewsletterAddress(currentUser.requireId(), id);
            redirectAttributes.addFlashAttribute("message",
                    "New address for \"" + feed.title() + "\": " + feedService.newsletterAddress(feed));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }
}
