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

    @Test
    void createsValidEpubLayoutWithUncompressedMimetypeFirst() throws Exception {
        byte[] epub = epubService.createEpub("Hello & Friends", "Author", "<p>Body<br>More</p>");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        String firstName;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(epub))) {
            ZipEntry first = zis.getNextEntry();
            firstName = first.getName();
            assertEquals("mimetype", firstName);
            // STORED (0). Some JDKs report method after reading; accept STORED or unset (-1) here
            // and assert uncompressed payload + ZIP local header below.
            entries.put(first.getName(), zis.readAllBytes());
            assertTrue(first.getMethod() == ZipEntry.STORED || first.getMethod() == -1);

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }

        assertEquals("application/epub+zip", new String(entries.get("mimetype"), StandardCharsets.US_ASCII));
        // Local file header compression method at offset 8 must be 0 (stored) for mimetype
        assertEquals(0x50, epub[0] & 0xff);
        assertEquals(0x4b, epub[1] & 0xff);
        assertEquals(0, epub[8] & 0xff);
        assertEquals(0, epub[9] & 0xff);
        assertTrue(entries.containsKey("META-INF/container.xml"));
        assertTrue(entries.containsKey("OEBPS/content.opf"));
        assertTrue(entries.containsKey("OEBPS/nav.xhtml"));
        assertTrue(entries.containsKey("OEBPS/article.xhtml"));

        String container = new String(entries.get("META-INF/container.xml"), StandardCharsets.UTF_8);
        assertTrue(container.contains("OEBPS/content.opf"));

        String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertTrue(opf.contains("Hello &amp; Friends"));
        assertTrue(opf.contains("article.xhtml"));
        assertTrue(opf.contains("nav.xhtml"));

        String article = new String(entries.get("OEBPS/article.xhtml"), StandardCharsets.UTF_8);
        assertTrue(article.contains("<p>Body<br />More</p>"));
        assertTrue(article.contains("Hello &amp; Friends"));
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(entries.get("OEBPS/article.xhtml")));
    }
}
