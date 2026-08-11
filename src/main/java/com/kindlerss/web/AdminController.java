package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.service.AdminTelemetryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Protected usage dashboard and per-user Kindle send controls. */
@Controller
public class AdminController {

    private final AdminTelemetryService telemetryService;
    private final int defaultDailyLimit;

    public AdminController(AdminTelemetryService telemetryService, AppProperties properties) {
        this.telemetryService = telemetryService;
        this.defaultDailyLimit = properties.limits().maxSendsPerDay();
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        model.addAttribute("summary", telemetryService.summary());
        model.addAttribute("users", telemetryService.users());
        model.addAttribute("defaultDailyLimit", defaultDailyLimit);
        return "admin";
    }

    @PostMapping("/admin/users/limit")
    public String updateLimit(@RequestParam("userId") long userId,
                              @RequestParam(value = "dailyLimit", required = false) Integer dailyLimit,
                              @RequestParam(value = "blockHours", required = false) Integer blockHours,
                              RedirectAttributes redirectAttributes) {
        try {
            telemetryService.updateLimit(userId, dailyLimit, blockHours);
            redirectAttributes.addFlashAttribute("message", "User send limit updated");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }
}
