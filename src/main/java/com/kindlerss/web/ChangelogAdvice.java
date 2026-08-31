package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.UserService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Optional;

/**
 * Publishes the changelog and, when the signed-in account has not yet seen the
 * latest release, the contents of the "what's new" notice.
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
        List<ChangelogCatalog.Release> releases = catalog.releases();
        model.addAttribute("changelogReleases", releases);
        Optional<ChangelogCatalog.Release> unseen = unseenRelease();
        model.addAttribute("whatsNew", unseen.orElse(null));
        model.addAttribute("whatsNewPrompt", unseen.isPresent());
    }

    private Optional<ChangelogCatalog.Release> unseenRelease() {
        return currentUser.details()
                .flatMap(details -> userService.findById(details.id()))
                .flatMap(user -> catalog.unseenSince(user.lastSeenChangelogId()));
    }
}
