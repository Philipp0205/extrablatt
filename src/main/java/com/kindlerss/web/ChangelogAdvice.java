package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ChangelogCatalog;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * When the signed-in account has not yet seen the latest release, publishes the
 * contents of the "what's new" notice. The full changelog lives on its own
 * Settings page.
 */
@ControllerAdvice(assignableTypes = {AppController.class, SettingsController.class})
public class ChangelogAdvice {

    private final ChangelogCatalog catalog;
    private final CurrentUser currentUser;
    private final UserService userService;

    public ChangelogAdvice(CurrentUser currentUser, UserService userService) {
        this.catalog = ChangelogCatalog.instance();
        this.currentUser = currentUser;
        this.userService = userService;
    }

    @ModelAttribute
    public void changelog(Model model) {
        Optional<ChangelogCatalog.Release> unseen = unseenRelease();
        model.addAttribute("whatsNew", unseen.orElse(null));
        model.addAttribute("whatsNewPrompt", unseen.isPresent());
        model.addAttribute("changelogRedirect", currentPath());
    }

    private static String currentPath() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return "/";
        }
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        String query = request.getQueryString();
        return query == null || query.isBlank() ? uri : uri + "?" + query;
    }

    private Optional<ChangelogCatalog.Release> unseenRelease() {
        return currentUser.details()
                .flatMap(details -> userService.findById(details.id()))
                .flatMap(user -> catalog.unseenSince(user.lastSeenChangelogId()));
    }
}
