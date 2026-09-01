package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Subscription;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.ChangelogCatalog;
import com.kindlerss.service.DataExportService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.Money;
import com.kindlerss.service.RetentionService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Per-account settings, split into short pages so a Kindle does not have to scroll. */
@Controller
public class SettingsController {

    private final UserService userService;
    private final ArticleService articleService;
    private final EntitlementService entitlementService;
    private final SubscriptionService subscriptionService;
    private final DataExportService dataExportService;
    private final RetentionService retentionService;
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public SettingsController(UserService userService, ArticleService articleService,
                              EntitlementService entitlementService,
                              SubscriptionService subscriptionService,
                              DataExportService dataExportService,
                              RetentionService retentionService,
                              CurrentUser currentUser,
                              AppProperties properties) {
        this.userService = userService;
        this.articleService = articleService;
        this.entitlementService = entitlementService;
        this.subscriptionService = subscriptionService;
        this.dataExportService = dataExportService;
        this.retentionService = retentionService;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("account", requireAccount());
        return "settings";
    }

    @GetMapping("/settings/kindle")
    public String kindle(Model model) {
        AppUser user = requireAccount();
        model.addAttribute("account", user);
        model.addAttribute("mailFrom", properties.mailFrom());
        addNewsletterAttributes(model, user);
        return "settings-kindle";
    }

    @GetMapping("/settings/reading")
    public String reading(Model model) {
        model.addAttribute("account", requireAccount());
        return "settings-reading";
    }

    @GetMapping("/settings/account")
    public String account(Model model) {
        model.addAttribute("account", requireAccount());
        return "settings-account";
    }

    @GetMapping("/settings/subscription")
    public String subscription(Model model) {
        if (!properties.billing().enabled()) {
            return "redirect:/settings";
        }
        long userId = currentUser.requireId();
        Entitlement entitlement = entitlementService.forUser(userId);
        addSubscriptionAttributes(model, userId, entitlement);
        return "settings-subscription";
    }

    @GetMapping("/settings/data")
    public String data(Model model) {
        model.addAttribute("retention", properties.retention());
        return "settings-data";
    }

    @GetMapping("/settings/changelog")
    public String changelog(Model model) {
        model.addAttribute("changelogReleases", ChangelogCatalog.instance().releases());
        return "settings-changelog";
    }

    /**
     * What the subscription menu shows. Prices are rendered here rather than in the
     * template so that the order page, the settings page and the confirmation e-mail
     * cannot end up quoting three different numbers.
     */
    private void addSubscriptionAttributes(Model model, long userId, Entitlement entitlement) {
        AppProperties.Billing billing = properties.billing();
        Subscription subscription = subscriptionService.forUser(userId);
        model.addAttribute("subscription", subscription);
        model.addAttribute("supporterPlan", entitlement.paid());
        model.addAttribute("monthlyPrice", Money.priceTag(billing.monthlyPriceCents()));
        model.addAttribute("yearlyPrice", Money.priceTag(billing.yearlyPriceCents()));
        model.addAttribute("yearlyPerMonth", Money.priceTag(billing.yearlyPricePerMonthCents()));
        model.addAttribute("freeSends", billing.freeMaxSendsPerMonth());
        model.addAttribute("freeFeeds", billing.freeMaxFeeds());
        // How many of the month's free articles are gone. A reader on the free plan
        // needs to know where they stand far more than they need to know the rule.
        if (entitlement.hasMonthlyCap()) {
            long used = articleService.countSentSince(userId, entitlementService.startOfCurrentMonth());
            model.addAttribute("usedThisMonth", used);
            model.addAttribute("leftThisMonth", Math.max(0, entitlement.maxSendsPerMonth() - used));
            model.addAttribute("resetsOn", DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
                    .format(entitlementService.nextResetDate()));
        }
        model.addAttribute("supporterSends", properties.limits().maxSendsPerDay());
        model.addAttribute("supporterFeeds", properties.limits().maxFeedsPerUser());
        model.addAttribute("checkoutConfigured", billing.checkoutConfigured());
        model.addAttribute("portalConfigured", billing.portalUrl() != null);
        if (subscription.currentPeriodEnd() != null) {
            model.addAttribute("renewsOn", DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
                    .format(subscription.currentPeriodEnd().atZone(ZoneId.of("Europe/Berlin"))));
        }
    }

    private void addNewsletterAttributes(Model model, AppUser user) {
        Entitlement entitlement = entitlementService.forUser(user.id());
        // A newsletter inbox needs a second provider on top of the outbound one, so it
        // belongs to the paid plan. An address already handed out keeps working —
        // losing a subscription changes allowances, it does not take things away.
        boolean newslettersEnabled = properties.newsletters().enabled()
                && (entitlement.newsletters() || user.newsletterInboundToken() != null);
        model.addAttribute("newslettersEnabled", newslettersEnabled);
        if (newslettersEnabled) {
            String token = userService.ensureNewsletterInboundToken(user.id());
            model.addAttribute("newsletterAddress", token + "@" + properties.newsletters().inboundDomain());
        }
    }

    private AppUser requireAccount() {
        return userService.findById(currentUser.requireId())
                .orElseThrow(() -> new IllegalStateException("Account not found"));
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
        return "redirect:/settings/kindle";
    }

    @PostMapping("/settings/reading")
    public String updateReading(@RequestParam(value = "markReadOnNextPage", required = false) String markReadOnNextPage,
                                RedirectAttributes redirectAttributes) {
        userService.updateMarkReadOnNextPage(currentUser.requireId(), markReadOnNextPage != null);
        redirectAttributes.addFlashAttribute("message", "Reading preference saved");
        return "redirect:/settings/reading";
    }

    @PostMapping("/settings/changelog/ack")
    public String acknowledgeChangelog(@RequestParam(value = "redirect", defaultValue = "/") String redirect) {
        ChangelogCatalog.instance().latestId()
                .ifPresent(id -> userService.acknowledgeChangelog(currentUser.requireId(), id));
        return "redirect:" + AppController.safeRedirect(redirect);
    }

    @PostMapping("/settings/newsletter-address/regenerate")
    public String regenerateNewsletterAddress(RedirectAttributes redirectAttributes) {
        if (!properties.newsletters().enabled()) {
            redirectAttributes.addFlashAttribute("error", "Newsletters are not configured on this server");
            return "redirect:/settings/kindle";
        }
        String token = userService.regenerateNewsletterInboundToken(currentUser.requireId());
        redirectAttributes.addFlashAttribute("message",
                "New newsletter address: " + token + "@" + properties.newsletters().inboundDomain());
        return "redirect:/settings/kindle";
    }

    /**
     * The copy of their own data a person is entitled to under Art. 15 GDPR, in the
     * machine-readable form Art. 20 asks for. Only ever the signed-in account's own.
     */
    @GetMapping("/account/export")
    public ResponseEntity<byte[]> exportData() {
        byte[] json = dataExportService.exportJson(currentUser.requireId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + dataExportService.filename() + "\"")
                .body(json);
    }

    @PostMapping("/account/delete")
    public String deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        long userId = currentUser.requireId();
        // Payment events carry no cascade — their ids have to outlive the account so a
        // replayed webhook stays a no-op — so what they hold is erased first, while
        // there is still a user id to find them by.
        retentionService.eraseForUser(userId);
        userService.deleteAccount(userId);
        // Logout clears both cookies the app sets — the session and remember-me — so
        // nothing of this account's is left in the browser. There is no display or
        // edition cookie to clear any more; those went with the accessibility edition.
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return "redirect:/login?deleted";
    }
}
