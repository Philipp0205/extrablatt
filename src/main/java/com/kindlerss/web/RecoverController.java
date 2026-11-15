package com.kindlerss.web;

import com.kindlerss.security.LinkSignIn;
import com.kindlerss.service.RecoverySignInService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/**
 * "Lost your recovery code?": a one-time sign-in link sent by e-mail.
 *
 * <p>The link is {@code /recover#t=<token>}: the token sits in the fragment, which
 * browsers never send, so it reaches no server log or Referer. {@code GET /recover}
 * only renders the page and never looks at a token, since mail scanners fetch links;
 * the token is spent by the {@code POST /recover} the reader submits.
 */
@Controller
public class RecoverController {

    private static final Logger log = LoggerFactory.getLogger(RecoverController.class);

    /** The same text whatever happened to the request, so the page confirms nothing. */
    static final String ACK = "If that address has an account with a confirmed e-mail, "
            + "we sent a sign-in link. It is valid for 20 minutes.";

    private final RecoverySignInService recovery;
    private final LinkSignIn linkSignIn;

    public RecoverController(RecoverySignInService recovery, LinkSignIn linkSignIn) {
        this.recovery = recovery;
        this.linkSignIn = linkSignIn;
    }

    @GetMapping("/recover/request")
    public String requestForm() {
        return "recover-request";
    }

    @PostMapping("/recover/request")
    public String request(@RequestParam(value = "email", required = false) String email,
                          RedirectAttributes redirectAttributes) {
        try {
            recovery.requestLink(email);
        } catch (RuntimeException e) {
            // Not even the class of failure is told to the visitor; nothing identifying is logged.
            log.warn("Sign-in link request failed: {}", e.getClass().getSimpleName());
        }
        // No "sentTo": echoing the address would make the flash differ per request.
        redirectAttributes.addFlashAttribute("instruction", ACK);
        redirectAttributes.addFlashAttribute("retryPath", "/recover/request");
        return "redirect:/check-email";
    }

    /** Never touches a token: the page only offers the form that does. */
    @GetMapping("/recover")
    public String form() {
        return "recover";
    }

    @PostMapping("/recover")
    public String consume(@RequestParam(value = "token", required = false) String token,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          RedirectAttributes redirectAttributes) {
        Optional<RecoverySignInService.SignIn> signIn;
        try {
            signIn = recovery.consume(token);
        } catch (RuntimeException e) {
            log.warn("Sign-in link could not be used: {}", e.getClass().getSimpleName());
            signIn = Optional.empty();
        }
        if (signIn.isEmpty()) {
            return "redirect:/recover?invalid";
        }
        linkSignIn.signIn(signIn.get().user(), request, response);
        // Like POST /settings/account/recovery-code: the plain code goes out once, in the flash.
        redirectAttributes.addFlashAttribute("newRecoveryCode", signIn.get().newRecoveryCode());
        return "redirect:/settings/account";
    }
}
