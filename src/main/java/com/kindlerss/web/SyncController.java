package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.sync.SyncService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Connecting an account to an external RSS provider, so feeds and read state follow it. */
@Controller
public class SyncController {

    private final SyncService syncService;
    private final CurrentUser currentUser;

    public SyncController(SyncService syncService, CurrentUser currentUser) {
        this.syncService = syncService;
        this.currentUser = currentUser;
    }

    @GetMapping("/settings/sync")
    public String sync(Model model) {
        var account = syncService.account(currentUser.requireId());
        model.addAttribute("syncAvailable", syncService.available());
        model.addAttribute("syncAccount", account.orElse(null));
        account.map(a -> a.lastSyncedAt()).ifPresent(at -> model.addAttribute("lastSynced",
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
                        .format(at.atZone(ZoneId.of("Europe/Berlin")))));
        return "settings-sync";
    }

    @PostMapping("/settings/sync/connect")
    public String connect(@RequestParam("baseUrl") String baseUrl,
                          @RequestParam("username") String username,
                          @RequestParam("password") String password,
                          RedirectAttributes redirectAttributes) {
        try {
            syncService.connect(currentUser.requireId(), baseUrl, username, password);
            redirectAttributes.addFlashAttribute("message",
                    "Connected. The first sync is running and may take a minute.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/sync";
    }

    @PostMapping("/settings/sync/now")
    public String syncNow(RedirectAttributes redirectAttributes) {
        syncService.syncSoon(currentUser.requireId());
        redirectAttributes.addFlashAttribute("message", "Sync started. Reload this page in a moment.");
        return "redirect:/settings/sync";
    }

    @PostMapping("/settings/sync/disconnect")
    public String disconnect(RedirectAttributes redirectAttributes) {
        syncService.disconnect(currentUser.requireId());
        redirectAttributes.addFlashAttribute("message",
                "Disconnected. Your feeds and articles here are unchanged.");
        return "redirect:/settings/sync";
    }
}
