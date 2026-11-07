package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an exception into a sentence a reader may see. Only exceptions whose
 * message the app writes for readers on purpose pass through; anything else (a
 * mail server's reply, a database error, a host name from a failed fetch) is
 * logged and replaced by the caller's fallback, so internals stay in the log.
 */
final class UserMessages {

    private static final Logger log = LoggerFactory.getLogger(UserMessages.class);

    private UserMessages() {
    }

    static String of(Exception e, String fallback) {
        String safe = safeMessage(e);
        if (safe != null) {
            return safe;
        }
        log.warn("Showing a generic error instead of: {}", String.valueOf(e), e);
        return fallback;
    }

    private static String safeMessage(Exception e) {
        if (e instanceof SafeHttpClient.FetchException fetch) {
            return fetch.userMessage();
        }
        if (e instanceof ArticleService.NotFoundException
                || e instanceof KindleMailService.SendRefusedException
                || e instanceof KindleMailService.DeliveryFailedException
                // Thrown by the app's own validation with a sentence for the reader. A
                // subclass (NumberFormatException, say) comes from a library instead.
                || (e != null && e.getClass() == IllegalArgumentException.class)) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? null : message;
        }
        return null;
    }
}
