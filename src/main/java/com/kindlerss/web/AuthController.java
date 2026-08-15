package com.kindlerss.web;

import com.kindlerss.config.SecurityConfig;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
    public String login(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object lastUsername = session.getAttribute(SecurityConfig.LAST_LOGIN_USERNAME);
            if (lastUsername != null) {
                // Put the tried e-mail back into the form after a wrong password, then
                // drop it so a later, clean visit starts with an empty field.
                model.addAttribute("email", lastUsername);
                session.removeAttribute(SecurityConfig.LAST_LOGIN_USERNAME);
            }
        }
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
            // Registration is transactional, so a failed verification e-mail also
            // rolls back the account: the address stays free to try again.
            redirectAttributes.addFlashAttribute("error",
                    "We could not send the confirmation e-mail, so the account was not created. "
                            + "Please try again in a moment.");
            redirectAttributes.addFlashAttribute("email", email);
            return "redirect:/register";
        }
        redirectAttributes.addFlashAttribute("sentTo", email);
        redirectAttributes.addFlashAttribute("instruction",
                "Open the confirmation link in that message to activate your account, then log in.");
        redirectAttributes.addFlashAttribute("retryPath", "/register");
        return "redirect:/check-email";
    }

    /**
     * Confirms that an e-mail went out. Reached only through a redirect that
     * carries the flash attributes; a direct visit has nothing to report.
     */
    @GetMapping("/check-email")
    public String checkEmail(Model model) {
        if (!model.containsAttribute("instruction")) {
            return "redirect:/login";
        }
        return "check-email";
    }

    @GetMapping("/verify")
    public String verify(@RequestParam(value = "token", required = false) String token,
                         Model model) {
        boolean verified = userService.verifyEmail(token);
        model.addAttribute("verified", verified);
        model.addAttribute("message", verified
                ? "E-mail confirmed. You can log in now."
                : "That confirmation link is invalid or has expired.");
        return "verify-result";
    }

    @GetMapping("/forgot-password")
    public String forgotForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgot(@RequestParam("email") String email, RedirectAttributes redirectAttributes) {
        userService.requestPasswordReset(email);
        // Worded so it reads the same whether or not the address has an account,
        // which keeps the page from confirming who is registered.
        redirectAttributes.addFlashAttribute("sentTo", email);
        redirectAttributes.addFlashAttribute("instruction",
                "If that address has an account, the message contains a link to set a new password. "
                        + "The link is valid for one hour.");
        redirectAttributes.addFlashAttribute("retryPath", "/forgot-password");
        return "redirect:/check-email";
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
