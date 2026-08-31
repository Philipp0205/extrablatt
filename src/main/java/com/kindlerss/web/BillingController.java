package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.BillingInterval;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.Money;
import com.kindlerss.service.SubscriptionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Ordering a subscription.
 *
 * <p>The order page is the app's own rather than the payment provider's, and it has
 * to be. § 312j Abs. 2 and 3 BGB require the essential terms — what the service is,
 * the total price, the term, how it renews and how to end it — to be shown
 * prominently immediately before ordering, and the button itself to say that
 * ordering costs money. § 312j Abs. 4 makes the consequence of getting that wrong
 * unusually blunt: the contract does not come into existence at all. A hosted
 * checkout labelled "Subscribe" is not something to gamble that on, so the reader
 * confirms here and only then goes to the provider to enter card details.
 */
@Controller
public class BillingController {

    private final SubscriptionService subscriptions;
    private final CurrentUser currentUser;
    private final AppProperties properties;

    public BillingController(SubscriptionService subscriptions, CurrentUser currentUser,
                             AppProperties properties) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/billing/order")
    public String order(@RequestParam(value = "interval", required = false) String intervalParam,
                        Model model) {
        if (!properties.billing().enabled()) {
            return "redirect:/settings";
        }
        BillingInterval interval = BillingInterval.parse(intervalParam);
        AppProperties.Billing billing = properties.billing();
        int totalCents = interval.isMonthly() ? billing.monthlyPriceCents() : billing.yearlyPriceCents();
        model.addAttribute("interval", interval.name().toLowerCase());
        model.addAttribute("monthly", interval.isMonthly());
        model.addAttribute("total", Money.priceTag(totalCents));
        model.addAttribute("perMonth", Money.priceTag(interval.isMonthly()
                ? billing.monthlyPriceCents() : billing.yearlyPricePerMonthCents()));
        model.addAttribute("supporterSends", properties.limits().maxSendsPerDay());
        model.addAttribute("supporterFeeds", properties.limits().maxFeedsPerUser());
        model.addAttribute("freeSends", billing.freeMaxSendsPerMonth());
        model.addAttribute("checkoutConfigured", billing.checkoutConfigured());
        return "billing-order";
    }

    /**
     * Takes the order and sends the reader to the provider to pay. Nothing is
     * granted here — the provider's signed callback does that — so a reader who
     * abandons the payment page is left exactly as they were.
     */
    @PostMapping("/billing/order")
    public String placeOrder(@RequestParam("interval") String intervalParam,
                             @RequestParam(value = "startNow", required = false) String startNow,
                             @RequestParam(value = "withdrawalAcknowledged", required = false)
                             String withdrawalAcknowledged,
                             RedirectAttributes redirectAttributes) {
        BillingInterval interval = BillingInterval.parse(intervalParam);
        if (!properties.billing().enabled()) {
            return "redirect:/settings";
        }
        // Both boxes have to be ticked by hand. § 356 Abs. 4 BGB only lets the
        // withdrawal right lapse on the consumer's express consent plus their
        // acknowledgement of the consequence, and a pre-ticked box is neither.
        if (startNow == null || withdrawalAcknowledged == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Please confirm both statements above before ordering.");
            redirectAttributes.addAttribute("interval", interval.name().toLowerCase());
            return "redirect:/billing/order";
        }
        try {
            String checkoutUrl = subscriptions.placeOrder(currentUser.requireId(), interval);
            return "redirect:" + checkoutUrl;
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/settings#subscription";
        }
    }

    /**
     * Where the provider returns the reader to. Deliberately says "being confirmed"
     * rather than "active": the webhook may not have arrived yet, and this page is
     * not allowed to be the thing that decides.
     */
    @GetMapping("/billing/return")
    public String returned(Model model) {
        model.addAttribute("subscription", subscriptions.forUser(currentUser.requireId()));
        return "billing-return";
    }

    /** Sends the reader to the provider's own page for changing a payment method. */
    @PostMapping("/billing/portal")
    public String portal(RedirectAttributes redirectAttributes) {
        String portalUrl = properties.billing().portalUrl();
        if (portalUrl == null) {
            redirectAttributes.addFlashAttribute("error",
                    "There is no payment portal configured. Write to us and we will sort it out.");
            return "redirect:/settings#subscription";
        }
        return "redirect:" + portalUrl;
    }
}
