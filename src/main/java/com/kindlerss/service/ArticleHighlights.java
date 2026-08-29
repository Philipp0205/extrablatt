package com.kindlerss.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pulls the shape of an article out of its own markup: the headings, the bullet
 * points and the pulled-out quotes, in the order they appear.
 *
 * <p>This is the part of the reader that was asked for most plainly — "extremely
 * minimal, or like highlights of the headings or bullet points", because "regular
 * format is just way too complicated for me to follow along". It is deliberately
 * extractive: every line shown is a line the publisher wrote. Nothing is
 * paraphrased, condensed or generated, so a trial result cannot quietly change
 * meaning on the way to a reader who is relying on it.
 */
@Component
public class ArticleHighlights {

    /** Beyond this the summary stops being a summary. */
    private static final int MAX_POINTS = 10;

    /** Below this an article has no structure worth extracting; fall back to lead sentences. */
    private static final int STRUCTURE_THRESHOLD = 3;

    private static final int MAX_POINT_LENGTH = 220;
    private static final int MIN_POINT_LENGTH = 4;

    /** A paragraph short enough to be a caption or a byline is not a lead. */
    private static final int MIN_LEAD_PARAGRAPH_LENGTH = 90;

    /** Past this, a run of bold text is a pull quote or a warning, not a heading. */
    private static final int MAX_HEADING_LENGTH = 120;

    /** Average adult reading speed, rounded down to something forgiving. */
    private static final int WORDS_PER_MINUTE = 200;

    /** List items that are page furniture rather than content. */
    private static final Set<String> BOILERPLATE = Set.of(
            "share", "share this", "subscribe", "sign up", "advertisement", "related",
            "read more", "next", "previous", "home", "menu", "search", "newsletter",
            "follow us", "comments", "print", "email", "save");

    /**
     * What kind of thing a point was in the article, so it can be coloured as one.
     *
     * <p>Not written out next to the point: most articles have too little structure
     * for the extractor to find anything but lead sentences, and a list where every
     * line is labelled "Opening" tells a reader nothing and costs a screen reader a
     * word before every point.
     */
    public enum Kind {
        HEADING,
        POINT,
        QUOTE,
        LEAD
    }

    public record Point(Kind kind, String text) {}

    /**
     * The key points, plus how long the whole article is — a reader deciding
     * whether to start something deserves to know it runs to twenty minutes.
     */
    public record Summary(List<Point> points, int words, int minutes) {
        public boolean hasPoints() {
            return !points.isEmpty();
        }
    }

    public Summary summarize(String html) {
        if (html == null || html.isBlank()) {
            return new Summary(List.of(), 0, 0);
        }
        Document document = Jsoup.parseBodyFragment(html);
        int words = countWords(document.body().text());
        List<Point> points = structuralPoints(document);
        if (points.size() < STRUCTURE_THRESHOLD) {
            points = merge(points, leadPoints(document));
        }
        return new Summary(List.copyOf(points), words, readingMinutes(words));
    }

    /** Headings, list items and quotes, in the order the article puts them. */
    private static List<Point> structuralPoints(Document document) {
        List<Point> points = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element element : document.body().select("h1, h2, h3, h4, h5, h6, li, blockquote, p")) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            // A list inside a list item would otherwise contribute its parent's
            // whole text as one point and then each child again. (Searching the
            // children rather than the element: jsoup's select matches self too.)
            if (!element.children().select("li, blockquote").isEmpty()) {
                continue;
            }
            // Plenty of publishers write their section headings as a bold paragraph
            // rather than as a heading tag. To a reader they are headings, and they
            // are usually the only structure such an article has.
            if ("p".equals(element.tagName()) && !isHeadingInDisguise(element)) {
                continue;
            }
            Kind kind = kindOf(element.tagName());
            String text = clean(element.text());
            if (text == null || isBoilerplate(text) || !seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            points.add(new Point(kind, text));
        }
        return points;
    }

    /**
     * A plain article — no subheadings, no bullets — still deserves a way in, so
     * the opening sentence of its first real paragraphs stands in for one.
     */
    private static List<Point> leadPoints(Document document) {
        List<Point> points = new ArrayList<>();
        for (Element paragraph : document.body().select("p")) {
            if (points.size() >= STRUCTURE_THRESHOLD) {
                break;
            }
            String text = paragraph.text().trim();
            if (text.length() < MIN_LEAD_PARAGRAPH_LENGTH || isBoilerplate(text)) {
                continue;
            }
            String sentence = clean(firstSentence(text));
            if (sentence != null) {
                points.add(new Point(Kind.LEAD, sentence));
            }
        }
        return points;
    }

    private static List<Point> merge(List<Point> structural, List<Point> leads) {
        List<Point> merged = new ArrayList<>(leads);
        merged.addAll(structural);
        return merged.size() > MAX_POINTS ? merged.subList(0, MAX_POINTS) : merged;
    }

    private static Kind kindOf(String tagName) {
        return switch (tagName) {
            case "li" -> Kind.POINT;
            case "blockquote" -> Kind.QUOTE;
            default -> Kind.HEADING;
        };
    }

    /** A short paragraph that is nothing but emphasised text is a section heading. */
    private static boolean isHeadingInDisguise(Element paragraph) {
        String text = paragraph.text().replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || text.length() > MAX_HEADING_LENGTH || text.endsWith(".")) {
            return false;
        }
        String emphasised = paragraph.select("strong, b, em, h1, h2, h3, h4, h5, h6")
                .stream().map(Element::text).reduce("", (a, b) -> (a + " " + b).trim())
                .replaceAll("\\s+", " ").trim();
        return !emphasised.isEmpty() && emphasised.equals(text);
    }

    private static String firstSentence(String text) {
        int end = -1;
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i + 1))) {
                end = i + 1;
                break;
            }
        }
        return end < 0 ? text : text.substring(0, end);
    }

    /** Collapses whitespace, drops what is too short to say anything, and caps the rest. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.length() < MIN_POINT_LENGTH) {
            return null;
        }
        if (text.length() <= MAX_POINT_LENGTH) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_POINT_LENGTH);
        return text.substring(0, cut < MAX_POINT_LENGTH / 2 ? MAX_POINT_LENGTH : cut).trim() + "…";
    }

    private static boolean isBoilerplate(String text) {
        return BOILERPLATE.contains(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim());
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    static int readingMinutes(int words) {
        if (words <= 0) {
            return 0;
        }
        return Math.max(1, Math.round((float) words / WORDS_PER_MINUTE));
    }
}
