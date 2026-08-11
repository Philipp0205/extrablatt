package com.kindlerss.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal EPUB 3 writer. The mimetype entry is stored uncompressed and written first.
 */
@Service
public class EpubService {

    private static final String MIMETYPE = "application/epub+zip";
    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public byte[] createEpub(String title, String author, String htmlBody) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeEpub(baos, title, author, htmlBody);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create EPUB", e);
        }
        return baos.toByteArray();
    }

    public void writeEpub(OutputStream out, String title, String author, String htmlBody) throws IOException {
        String safeTitle = blankToDefault(title, "Article");
        String safeAuthor = blankToDefault(author, "Unknown");
        String bookId = "urn:uuid:" + UUID.randomUUID();
        String modified = MODIFIED.format(Instant.now());
        String articleXhtml = wrapArticle(safeTitle, htmlBody);
        String contentOpf = buildOpf(safeTitle, safeAuthor, bookId, modified);
        String navXhtml = buildNav(safeTitle);
        // EPUB package parts are fixed scaffolding; inlining the XML keeps this
        // writer dependency-free. A full EPUB library would be overkill here.
        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;

        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            // EPUB requires mimetype as first entry, stored (no compression)
            writeStored(zos, "mimetype", MIMETYPE.getBytes(StandardCharsets.US_ASCII));
            writeDeflated(zos, "META-INF/container.xml", containerXml);
            writeDeflated(zos, "OEBPS/content.opf", contentOpf);
            writeDeflated(zos, "OEBPS/nav.xhtml", navXhtml);
            writeDeflated(zos, "OEBPS/article.xhtml", articleXhtml);
        } finally {
            zos.finish();
        }
    }

    private static void writeStored(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeDeflated(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String wrapArticle(String title, String bodyHtml) {
        String body = toXhtml(bodyHtml);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <title>%s</title>
                  <style>
                    body { font-family: serif; line-height: 1.5; margin: 1em; }
                    img { max-width: 100%%; height: auto; }
                    h1 { font-size: 1.4em; }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  %s
                </body>
                </html>
                """.formatted(escapeXml(title), escapeXml(title), body);
    }

    private static String toXhtml(String bodyHtml) {
        Document document = Jsoup.parseBodyFragment(bodyHtml == null ? "" : bodyHtml);
        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        return document.body().html();
    }

    private static String buildOpf(String title, String author, String bookId, String modified) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="BookId">%s</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:creator>%s</dc:creator>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">%s</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="article" href="article.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="article"/>
                  </spine>
                </package>
                """.formatted(escapeXml(bookId), escapeXml(title), escapeXml(author), modified);
    }

    private static String buildNav(String title) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <title>Navigation</title>
                </head>
                <body>
                  <nav epub:type="toc" id="toc">
                    <h1>Contents</h1>
                    <ol>
                      <li><a href="article.xhtml">%s</a></li>
                    </ol>
                  </nav>
                </body>
                </html>
                """.formatted(escapeXml(title));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
