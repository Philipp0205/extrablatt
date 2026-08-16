package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubServiceTest {

    private final EpubService epubService = new EpubService();

    /**
     * The EPUB layout is the library's job now, so this guards the parts a reader
     * (and Amazon's converter) rejects an otherwise fine file over.
     */
    @Test
    void createsValidEpubLayoutWithUncompressedMimetypeFirst() throws Exception {
        byte[] epub = epubService.createEpub("Hello & Friends", "Jane Doe", "<p>Body<br>More</p>");
        Map<String, byte[]> entries = entries(epub);

        assertEquals("mimetype", entries.keySet().iterator().next());
        assertEquals("application/epub+zip", new String(entries.get("mimetype"), StandardCharsets.US_ASCII));
        // Local file header: PK signature, then a compression method of 0 (stored).
        assertEquals(0x50, epub[0] & 0xff);
        assertEquals(0x4b, epub[1] & 0xff);
        assertEquals(0, epub[8] & 0xff);
        assertEquals(0, epub[9] & 0xff);

        assertTrue(entries.containsKey("META-INF/container.xml"));
        assertTrue(entries.containsKey("OEBPS/content.opf"));
        assertTrue(entries.containsKey("OEBPS/toc.ncx"));
        assertTrue(entries.containsKey("OEBPS/article.xhtml"));
        assertTrue(entries.containsKey("OEBPS/style.css"));

        assertTrue(text(entries, "META-INF/container.xml").contains("OEBPS/content.opf"));

        String opf = text(entries, "OEBPS/content.opf");
        assertTrue(opf.contains("Hello &amp; Friends"));
        assertTrue(opf.contains(">Jane Doe<"));
        assertTrue(opf.contains("article.xhtml"));
        assertTrue(opf.contains("toc.ncx"));

        String article = text(entries, "OEBPS/article.xhtml");
        assertTrue(article.contains("<p>Body<br />More</p>"));
        assertTrue(article.contains("Hello &amp; Friends"));
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(entries.get("OEBPS/article.xhtml")));
    }

    /** Feeds often name a publication rather than a person, and some name nobody. */
    @Test
    void fallsBackToPlaceholdersAndKeepsSingleWordBylinesIntact() throws Exception {
        Map<String, byte[]> named = entries(epubService.createEpub("Title", "Reuters", "<p>Body</p>"));
        assertTrue(text(named, "OEBPS/content.opf").contains("Reuters"));

        Map<String, byte[]> blank = entries(epubService.createEpub("  ", null, null));
        String opf = text(blank, "OEBPS/content.opf");
        assertTrue(opf.contains("<dc:title>Article</dc:title>"));
        assertTrue(opf.contains("Unknown"));
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(blank.get("OEBPS/article.xhtml")));
    }

    private static Map<String, byte[]> entries(byte[] epub) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(epub))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }
}
