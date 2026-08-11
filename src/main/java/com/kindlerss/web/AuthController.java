package com.kindlerss.web;

import com.kindlerss.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Registration, e-mail verification, password reset, and static legal pages. */
@Controller
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam("email") String email,
                           @RequestParam("password") String password,
                           RedirectAttributes redirectAttributes) {
        try {
            userService.register(email, password);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            redirectAttributes.addFlashAttribute("email", email);
            return "redirect:/register";
        } catch (RuntimeException e) {
            // Account was created but the verification e-mail failed to send; keep
            // the message generic and let the user request a new link via reset.
            redirectAttributes.addFlashAttribute("error",
                    "Account created but the confirmation e-mail could not be sent. Try again shortly.");
            return "redirect:/register";
        }
        redirectAttributes.addFlashAttribute("message",
                "Check your inbox to confirm your e-mail, then log in.");
        return "redirect:/login";
    }

    @GetMapping("/verify")
    public String verify(@RequestParam(value = "token", required = false) String token,
                         RedirectAttributes redirectAttributes) {
        if (userService.verifyEmail(token)) {
            redirectAttributes.addFlashAttribute("message", "E-mail confirmed. You can log in now.");
        } else {
            redirectAttributes.addFlashAttribute("error",
                    "That confirmation link is invalid or has expired.");
        }
        return "redirect:/login";
    }

    @GetMapping("/forgot-password")
    public String forgotForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgot(@RequestParam("email") String email, RedirectAttributes redirectAttributes) {
        userService.requestPasswordReset(email);
        redirectAttributes.addFlashAttribute("message",
                "If that address has an account, a reset link is on its way.");
        return "redirect:/login";
    }

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(value = "token", required = false) String token, Model model) {
        model.addAttribute("token", token == null ? "" : token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String reset(@RequestParam("token") String token,
                        @RequestParam("password") String password,
                        RedirectAttributes redirectAttributes) {
        try {
            if (userService.resetPassword(token, password)) {
                redirectAttributes.addFlashAttribute("message",
                        "Password updated. Log in with your new password.");
                return "redirect:/login";
            }
            redirectAttributes.addFlashAttribute("error",
                    "That reset link is invalid or has expired.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            redirectAttributes.addAttribute("token", token);
            return "redirect:/reset-password";
        }
        return "redirect:/forgot-password";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "privacy";
    }

    @GetMapping("/terms")
    public String terms() {
        return "terms";
    }
}
