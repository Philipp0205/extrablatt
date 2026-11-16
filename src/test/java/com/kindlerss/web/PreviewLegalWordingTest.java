package com.kindlerss.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legal footer and the "Your trial has ended" notice in the preview client carry the
 * website's wording, word for word (it is frozen). Every string is checked against the
 * website's fragment and against {@code legal.js}, where it has to be one single-line literal.
 */
class PreviewLegalWordingTest {

    private static final String FOOTER = "templates/fragments/site-footer.html";
    private static final String DIALOG = "templates/fragments/trial-ended-dialog.html";
    private static final String SCRIPT = "static/js/preview/legal.js";

    private static final List<String> FOOTER_LINKS = List.of("Imprint", "Terms", "Privacy",
            "Right of withdrawal", "Cancel your contract here");
    private static final List<String> FOOTER_HREFS = List.of("/imprint", "/terms", "/privacy",
            "/withdrawal", "/cancel");

    private static final String TITLE = "Your trial has ended";
    private static final String THANKS = "Thank you for using Extrablatt.";
    private static final String EXPLAIN = "Reading in the browser is not limited. Sending articles to your Kindle, "
            + "and adding more feeds, needs a subscription.";
    private static final String SUBSCRIBE = "Subscribe";
    private static final String SUBSCRIBE_HREF = "/settings/subscription";
    private static final String CLOSE = "Close";

    private static String text(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String squashed(String value) {
        return value.replaceAll("\\s+", " ");
    }

    @Test
    void theFooterLinksAreTheWebsitesInTheSameOrder() throws IOException {
        String footer = text(FOOTER);
        String script = text(SCRIPT);

        int footerAt = -1;
        int scriptAt = -1;
        for (int i = 0; i < FOOTER_LINKS.size(); i++) {
            String label = FOOTER_LINKS.get(i);
            String href = FOOTER_HREFS.get(i);
            String site = "<a th:href=\"@{" + href + "}\">" + label + "</a>";
            assertTrue(footer.contains(site), "the website's footer has: " + site);
            assertTrue(footer.indexOf(site) > footerAt, label + " is in order on the website");
            footerAt = footer.indexOf(site);

            String preview = "link('" + href + "', '" + label + "')";
            assertTrue(script.contains(preview), "legal.js has: " + preview);
            assertTrue(script.indexOf(preview) > scriptAt, label + " is in order in legal.js");
            scriptAt = script.indexOf(preview);
        }
        assertTrue(squashed(footer).contains("<footer th:fragment=\"siteFooter\" class=\"site-legal\"> "
                + "<nav aria-label=\"Legal\">"), "the website's footer is a nav labelled Legal");
        assertTrue(script.contains("'aria-label': 'Legal'"), "legal.js labels the nav Legal");
    }

    @Test
    void theWithdrawalAndCancelLinksOnlyFollowBillingBeingOn() throws IOException {
        String footer = squashed(text(FOOTER));
        String script = text(SCRIPT);

        assertTrue(footer.contains("<th:block th:if=\"${billingEnabled}\">"), "the website gates them on billing");
        int gate = script.indexOf("account.billingEnabled === true");
        assertTrue(gate > 0, "legal.js gates them on billingEnabled");
        assertTrue(gate < script.indexOf("link('/withdrawal'"), "withdrawal comes after the gate");
        assertTrue(gate < script.indexOf("link('/cancel'"), "cancel comes after the gate");
        // The always-present links are built before the gate, not inside it.
        assertTrue(script.indexOf("link('/privacy'") < gate, "Privacy is not behind the gate");
    }

    @Test
    void theTrialEndedNoticeIsTheWebsitesWordForWord() throws IOException {
        String dialog = squashed(text(DIALOG));
        String script = text(SCRIPT);

        assertTrue(dialog.contains("<h2>" + TITLE + "</h2>"), "website title");
        assertTrue(dialog.contains("<p>" + THANKS + "</p>"), "website thanks line");
        assertTrue(dialog.contains("<p class=\"meta\"> " + EXPLAIN + " </p>"), "website explanation");
        assertTrue(dialog.contains("th:href=\"@{" + SUBSCRIBE_HREF + "}\">" + SUBSCRIBE + "</a>"), "website Subscribe link");
        assertTrue(dialog.contains("<button class=\"btn\" type=\"submit\">" + CLOSE + "</button>"), "website Close button");

        for (String wording : List.of(TITLE, THANKS, EXPLAIN, SUBSCRIBE, CLOSE)) {
            assertTrue(script.contains("'" + wording + "'"), "legal.js has the literal '" + wording + "'");
        }
        assertTrue(script.contains("href: '" + SUBSCRIBE_HREF + "'"), "legal.js sends Subscribe to the subscription page");
    }

    @Test
    void everyWordingLiteralSitsOnOneLineInTheScript() throws IOException {
        String script = text(SCRIPT);

        for (String wording : List.of(TITLE, THANKS, EXPLAIN)) {
            String literal = "'" + wording + "'";
            int at = script.indexOf(literal);
            assertTrue(at >= 0, "legal.js has " + literal);
            assertFalse(literal.contains("\n"), "the literal is a single line");
            int lineStart = script.lastIndexOf('\n', at) + 1;
            int lineEnd = script.indexOf('\n', at);
            assertTrue(script.substring(lineStart, lineEnd).contains(literal), "the literal does not wrap");
        }
        // No string is built by concatenation, which would break the verbatim search.
        assertFalse(script.contains("' +\n"), "legal.js joins no wording with +");
        assertFalse(script.contains("+ '"), "legal.js joins no wording with +");
    }

    @Test
    void closingTheNoticeIsAcknowledgedOnTheServer() throws IOException {
        String script = text(SCRIPT);

        assertTrue(script.contains("'/api/v1/account/trial-ended/ack'"), "legal.js posts the acknowledgement");
        assertTrue(script.contains("trialEndedPrompt"), "legal.js reads trialEndedPrompt from the account");
    }
}
