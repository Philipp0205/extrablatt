package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.security.CurrentUser;
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
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public SettingsController(UserService userService, CurrentUser currentUser, AppProperties properties) {
        this.userService = userService;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        AppUser user = userService.findById(currentUser.requireId())
                .orElseThrow(() -> new IllegalStateException("Account not found"));
        model.addAttribute("account", user);
        model.addAttribute("mailFrom", properties.mailFrom());
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
        return "redirect:/settings";
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
