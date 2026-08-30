package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.AdminTelemetryService;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Per-account settings: Kindle destination address and account deletion. */
@Controller
public class SettingsController {

    private final UserService userService;
    private final ArticleService articleService;
    private final AdminTelemetryService telemetryService;
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public SettingsController(UserService userService, ArticleService articleService,
                              AdminTelemetryService telemetryService, CurrentUser currentUser,
                              AppProperties properties) {
        this.userService = userService;
        this.articleService = articleService;
        this.telemetryService = telemetryService;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/settings")
    public String settings(@RequestParam(value = "view", defaultValue = "accounts") String view,
                           Model model) {
        long userId = currentUser.requireId();
        AppUser user = userService.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Account not found"));
        model.addAttribute("account", user);
        model.addAttribute("mailFrom", properties.mailFrom());
        model.addAttribute("totalSent", articleService.countSentTotal(userId));
        boolean newslettersEnabled = properties.newsletters().enabled();
        model.addAttribute("newslettersEnabled", newslettersEnabled);
        if (newslettersEnabled) {
            String token = userService.ensureNewsletterInboundToken(userId);
            model.addAttribute("newsletterAddress", token + "@" + properties.newsletters().inboundDomain());
        }
        boolean admin = currentUser.details().map(AppUserDetails::admin).orElse(false);
        String activeView = switch (view) {
            case "kindle", "version", "support", "delete" -> view;
            case "telemetry" -> admin ? view : "accounts";
            default -> "accounts";
        };
        model.addAttribute("activeView", activeView);
        if (admin) {
            model.addAttribute("summary", telemetryService.summary());
            model.addAttribute("users", telemetryService.users());
            model.addAttribute("defaultDailyLimit", properties.limits().maxSendsPerDay());
        }
        return "settings";
    }


    @PostMapping("/settings/kindle-email")
    public String updateKindleEmail(@RequestParam(value = "kindleEmail", required = false) String kindleEmail,
                                    RedirectAttributes redirectAttributes) {
        try {
            userService.updateKindleEmail(currentUser.requireId(), kindleEmail);
            redirectAttributes.addFlashAttribute("message", "Kindle e-mail updated");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings?view=kindle";
    }

    @PostMapping("/settings/newsletter-address/regenerate")
    public String regenerateNewsletterAddress(RedirectAttributes redirectAttributes) {
        if (!properties.newsletters().enabled()) {
            redirectAttributes.addFlashAttribute("error", "Newsletters are not configured on this server");
            return "redirect:/settings?view=kindle";
        }
        String token = userService.regenerateNewsletterInboundToken(currentUser.requireId());
        redirectAttributes.addFlashAttribute("message",
                "New newsletter address: " + token + "@" + properties.newsletters().inboundDomain());
        return "redirect:/settings?view=kindle";
    }

    @PostMapping("/account/delete")
    public String deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        long userId = currentUser.requireId();
        userService.deleteAccount(userId);
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return "redirect:/login?deleted";
    }
}
