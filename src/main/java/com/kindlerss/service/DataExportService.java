package com.kindlerss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kindlerss.config.AppProperties;
import com.kindlerss.repository.DataExportRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the copy of their data that a person can ask for under Art. 15 GDPR, in the
 * structured, machine-readable form Art. 20 asks for.
 *
 * <p>Self-service rather than a mailbox to write to, for a reason that is practical
 * rather than legal: a request that has to be handled by hand within a month is a
 * promise the operator has to remember to keep, and a download link is a promise the
 * software keeps by itself.
 *
 * <p>Pretty-printed on purpose. This is a document a person reads to find out what is
 * held about them, so it should be legible without tooling.
 */
@Service
public class DataExportService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataExportRepository exports;
    private final AppProperties properties;

    public DataExportService(DataExportRepository exports, AppProperties properties) {
        this.exports = exports;
        this.properties = properties;
    }

    public byte[] exportJson(long userId) {
        Map<String, Object> account = exports.account(userId);
        if (account == null) {
            throw new IllegalStateException("Account not found");
        }
        String email = String.valueOf(account.get("email"));

        // Newsletter inboxes are shown as the full address, since the bare token means
        // nothing to a reader and the address is the thing they handed out.
        Object token = account.remove("newsletter_inbound_token");
        if (token != null && properties.newsletters().enabled()) {
            account.put("newsletter_inbox_address", token + "@" + properties.newsletters().inboundDomain());
        } else if (token != null) {
            account.put("newsletter_inbox_token", token);
        }

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exported_at", Instant.now().toString());
        export.put("about", about());
        export.put("account", account);
        export.put("subscription", exports.subscription(userId));
        export.put("administrative_send_limit", exports.sendLimit(userId));
        export.put("feeds", exports.feeds(userId));
        export.put("articles", exports.articles(userId));
        export.put("kindle_deliveries", exports.sendHistory(userId));
        export.put("cancellations", exports.cancellations(userId, email));
        export.put("payment_events", exports.billingEvents(userId));

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the data export", e);
        }
    }

    /** A file that arrives without explanation is not much of an answer. */
    private Map<String, Object> about() {
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("description", "Everything Extrablatt holds about this account.");
        about.put("article_text", "Article titles, addresses and your reading state are "
                + "included. The article text itself is not: it is the publisher's writing "
                + "rather than data about you, and it is still at the address in each entry.");
        about.put("password", "Not included. Passwords are stored only as a bcrypt hash, "
                + "which cannot be turned back into your password.");
        about.put("payments", "Card details never reach Extrablatt. Your payment provider "
                + "holds those, and you can ask them for a copy separately.");
        about.put("questions", "Write to us at the address on the imprint page.");
        return about;
    }

    /** Filename for the download, dated so two exports do not overwrite each other. */
    public String filename() {
        return "extrablatt-data-" + Instant.now().toString().substring(0, 10) + ".json";
    }
}
