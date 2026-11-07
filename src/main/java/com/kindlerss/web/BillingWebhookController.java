package com.kindlerss.web;

import com.kindlerss.service.BillingWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * The payment provider's callback, and the only thing in the app that can turn an
 * account into a subscriber.
 *
 * <p>It follows the shape of {@link NewsletterInboundController} — no session, no
 * CSRF token, unauthenticated as far as Spring Security is concerned — with one
 * difference: a shared secret in the URL is not good enough for money, so the
 * caller is authenticated by its HMAC signature over the raw body. The body is
 * therefore taken as bytes and hashed before anything parses it; re-serializing
 * parsed JSON would change the bytes and break every signature.
 *
 * <p>The body is read only once a signature header is present, and never past
 * {@link #MAX_BODY_BYTES}: payment events are a few kilobytes, so anything bigger
 * is not one and must not be buffered for whoever sent it.
 */
@RestController
public class BillingWebhookController {

    /** Stripe documents events well under this; Paddle's are smaller still. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final BillingWebhookService webhooks;

    public BillingWebhookController(BillingWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/webhooks/billing")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestHeader(value = "Paddle-Signature", required = false) String paddleSignature,
            HttpServletRequest request) {
        if (!webhooks.configured()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Billing is not configured"));
        }
        String signature = stripeSignature != null ? stripeSignature : paddleSignature;
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid signature"));
        }
        byte[] body;
        try {
            body = BoundedBody.read(request, MAX_BODY_BYTES);
        } catch (BoundedBody.TooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Payload too large"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the request body"));
        }
        BillingWebhookService.Result result = webhooks.handle(signature, body);
        return switch (result) {
            case ACCEPTED -> ResponseEntity.ok(Map.of("status", "ok"));
            // Authentic but about something we do not track. A 200 stops the provider
            // retrying an event it will never get a different answer to.
            case IGNORED -> ResponseEntity.ok(Map.of("status", "ignored"));
            case REJECTED -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid signature"));
            // Recorded, and deliberately not a 5xx: the provider retrying will not
            // make it succeed, and the row carries the reason it failed.
            case FAILED -> ResponseEntity.ok(Map.of("status", "recorded", "applied", false));
        };
    }
}
