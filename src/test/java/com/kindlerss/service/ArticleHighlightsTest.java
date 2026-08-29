package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleHighlightsTest {

    private final ArticleHighlights highlights = new ArticleHighlights();

    @Test
    void takesTheArticlesOwnHeadingsBulletsAndQuotesInOrder() {
        ArticleHighlights.Summary summary = highlights.summarize("""
                <p>An opening paragraph that sets out what the study was about.</p>
                <h2>What the trial found</h2>
                <ul>
                  <li>Vision improved in four in ten participants</li>
                  <li>No serious side effects were reported</li>
                </ul>
                <blockquote>We were not expecting this, the lead author said.</blockquote>
                """);

        List<ArticleHighlights.Point> points = summary.points();
        assertEquals(4, points.size());
        assertEquals(ArticleHighlights.Kind.HEADING, points.get(0).kind());
        assertEquals("What the trial found", points.get(0).text());
        assertEquals(ArticleHighlights.Kind.POINT, points.get(1).kind());
        assertEquals("Vision improved in four in ten participants", points.get(1).text());
        assertEquals(ArticleHighlights.Kind.QUOTE, points.get(3).kind());
    }

    @Test
    void everyPointIsWordForWordFromTheArticle() {
        String html = "<h2>Enrolment closes in June</h2><p>Some prose about the study.</p>";
        ArticleHighlights.Summary summary = highlights.summarize(html);

        for (ArticleHighlights.Point point : summary.points()) {
            assertTrue(html.contains(point.text()), "invented text: " + point.text());
        }
    }

    @Test
    void picksUpSectionHeadingsThatArePublishedAsBoldParagraphs() {
        // How several real newsrooms mark their sections up, ScienceDaily among them.
        ArticleHighlights.Summary summary = highlights.summarize("""
                <p>An opening paragraph long enough to be the lead of the article itself.</p>
                <p><strong>Searching for biological signs</strong></p>
                <p>More prose that carries the section and runs to a reasonable length.</p>
                <p><strong>What happens next</strong></p>
                <p>This paragraph <strong>emphasises</strong> a word but is not a heading.</p>
                """);

        assertEquals(2, summary.points().size());
        assertEquals(ArticleHighlights.Kind.HEADING, summary.points().get(0).kind());
        assertEquals("Searching for biological signs", summary.points().get(0).text());
        assertEquals("What happens next", summary.points().get(1).text());
    }

    @Test
    void aWholeBoldSentenceIsNotAHeading() {
        ArticleHighlights.Summary summary = highlights.summarize(
                "<p><strong>This is a bold sentence, and it ends like one.</strong></p>"
                        + "<p><b>" + "long ".repeat(40) + "</b></p>");

        assertFalse(summary.points().stream()
                .anyMatch(point -> point.kind() == ArticleHighlights.Kind.HEADING));
    }

    @Test
    void anArticleWithNoStructureFallsBackToItsOpeningSentences() {
        ArticleHighlights.Summary summary = highlights.summarize("""
                <p>Researchers at nine hospitals have reported the results of a two-year study
                   into a treatment for retinal degeneration. The work began in 2023.</p>
                <p>The second paragraph runs on at similar length and adds the detail that the
                   participants were followed up every three months throughout.</p>
                """);

        assertEquals(2, summary.points().size());
        assertEquals(ArticleHighlights.Kind.LEAD, summary.points().get(0).kind());
        assertTrue(summary.points().get(0).text().endsWith("degeneration."),
                "should stop at the end of the first sentence: " + summary.points().get(0).text());
    }

    @Test
    void skipsNavigationDressedUpAsAList() {
        ArticleHighlights.Summary summary = highlights.summarize(
                "<ul><li>Share</li><li>Subscribe</li><li>The treatment reached phase three</li></ul>");

        assertEquals(1, summary.points().size());
        assertEquals("The treatment reached phase three", summary.points().get(0).text());
    }

    @Test
    void doesNotRepeatAListItemThroughItsParent() {
        ArticleHighlights.Summary summary = highlights.summarize(
                "<ul><li>Outer point<ul><li>Inner point</li></ul></li></ul>");

        assertEquals(List.of("Inner point"), summary.points().stream().map(ArticleHighlights.Point::text).toList());
    }

    @Test
    void longPointsAreCutAtAWordBoundary() {
        String sentence = "word ".repeat(120).trim();
        ArticleHighlights.Summary summary = highlights.summarize("<h2>" + sentence + "</h2>");

        String text = summary.points().get(0).text();
        assertTrue(text.length() <= 221, "was " + text.length());
        assertTrue(text.endsWith("…"));
        assertFalse(text.contains("  "));
    }

    @Test
    void reportsHowLongTheWholeArticleIs() {
        ArticleHighlights.Summary summary =
                highlights.summarize("<p>" + "word ".repeat(600).trim() + "</p>");

        assertEquals(600, summary.words());
        assertEquals(3, summary.minutes());
        assertEquals(0, highlights.summarize(null).minutes());
        // Anything at all takes a minute; nothing takes none.
        assertEquals(1, ArticleHighlights.readingMinutes(12));
        assertEquals(0, ArticleHighlights.readingMinutes(0));
    }

    @Test
    void anEmptyArticleHasNothingToShow() {
        assertFalse(highlights.summarize("").hasPoints());
        assertFalse(highlights.summarize(null).hasPoints());
    }
}
