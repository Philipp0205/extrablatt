package com.kindlerss.web;

import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sending an article to a Kindle, from the Send button to the bytes of the mail. The
 * real {@code KindleMailService}, {@code EpubService}, repositories, filters and
 * security chain run; only the mail transport is replaced, by one that keeps what it
 * was asked to send.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KindleSendEndToEndIntegrationTest {

    private static final String KINDLE_UA = "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us)"
            + " AppleWebKit/531.2+ (KHTML, like Gecko) Version/5.0 Safari/533.2+ Kindle/3.0+";
    private static final String PASSWORD = "kindle-send-password-1";
    private static final String TITLE = "Lighthouses of the Northern Coast";
    private static final String FIRST_PARAGRAPH = "The first keeper climbed the spiral stair every dusk.";
    private static final String SECOND_PARAGRAPH = "Fog rolled in from the sea long before the lamp was lit.";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("app.mail-from", () -> "noreply@example.com");
        registry.add("app.remember-me-key", () -> "kindle-send-e2e-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean JavaMailSender mailSender;

    private String email;
    private String clientIp;

    @BeforeEach
    void captureMailInsteadOfSendingIt() {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        email = "kse-" + UUID.randomUUID() + "@example.com";
        // The login form is rate limited per client address: each test is its own client.
        clientIp = "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    /** A verified account; the Kindle address is saved only when one is given. */
    private long seedUser(String kindleEmail) {
        long id = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(id);
        if (kindleEmail != null) {
            users.updateKindleEmail(id, kindleEmail);
        }
        return id;
    }

    private long seedArticle(long userId) {
        Feed feed = feeds.insert(userId, "Coastal Notes", "https://coast.example/feed",
                "https://coast.example", null);
        long id = articles.insert(feed.id(), "guid-" + UUID.randomUUID(), TITLE,
                "https://coast.example/lighthouses", "Ada Keeper", Instant.now(), "<p>Summary</p>", null);
        articles.updateExtractedContent(id,
                "<p>" + FIRST_PARAGRAPH + "</p><p>" + SECOND_PARAGRAPH + "</p>");
        return id;
    }

    private MockHttpSession signIn() throws Exception {
        MvcResult login = mockMvc.perform(post("/login").servletPath("/login").with(csrf())
                        .header("User-Agent", KINDLE_UA)
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .param("username", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "signing in should have created a session");
        return session;
    }

    /**
     * The form the e-ink article page renders: POST /articles/{id}/send with the CSRF
     * token. A successful send goes back to the article with the "Sent to Kindle" message.
     */
    private void pressSend(MockHttpSession session, long articleId) throws Exception {
        mockMvc.perform(post("/articles/{id}/send", articleId).session(session).with(csrf())
                        .header("User-Agent", KINDLE_UA)
                        .param("images", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/" + articleId))
                .andExpect(flash().attribute("message", "Sent to Kindle"));
    }

    @Test
    void sendingAnArticleMailsAValidEpubToTheKindleAddress() throws Exception {
        long userId = seedUser("reader@kindle.com");
        long articleId = seedArticle(userId);
        MockHttpSession session = signIn();

        pressSend(session, articleId);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();

        assertEquals(1, message.getAllRecipients().length);
        assertEquals("reader@kindle.com", ((InternetAddress) message.getAllRecipients()[0]).getAddress());
        assertEquals(1, message.getFrom().length);
        assertEquals("noreply@example.com", ((InternetAddress) message.getFrom()[0]).getAddress());

        MimeMultipart parts = (MimeMultipart) message.getContent();
        List<BodyPart> attachments = new ArrayList<>();
        for (int i = 0; i < parts.getCount(); i++) {
            BodyPart part = parts.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                attachments.add(part);
            }
        }
        assertEquals(1, attachments.size(), "exactly one attachment");
        String filename = attachments.get(0).getFileName();
        assertTrue(filename != null && filename.endsWith(".epub"), "attachment name: " + filename);

        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] epub = attachments.get(0).getInputStream().readAllBytes();
        int firstEntryMethod = -1;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epub))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (files.isEmpty()) {
                    firstEntryMethod = entry.getMethod();
                }
                files.put(entry.getName(), zip.readAllBytes());
            }
        }

        assertEquals("mimetype", files.keySet().iterator().next(), "mimetype is the first entry");
        assertEquals(ZipEntry.STORED, firstEntryMethod, "mimetype is stored, not compressed");
        assertEquals("application/epub+zip", new String(files.get("mimetype"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("META-INF/container.xml"), "container.xml: " + files.keySet());
        assertTrue(files.keySet().stream().anyMatch(name -> name.endsWith(".opf")), "an OPF: " + files.keySet());
    }

    @Test
    void theEpubCarriesTheArticleTitleAndBothParagraphs() throws Exception {
        long userId = seedUser("reader@kindle.com");
        long articleId = seedArticle(userId);

        pressSend(signIn(), articleId);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(sent.capture());
        sent.getValue().saveChanges();
        MimeMultipart parts = (MimeMultipart) sent.getValue().getContent();
        byte[] epub = null;
        for (int i = 0; i < parts.getCount(); i++) {
            if (Part.ATTACHMENT.equalsIgnoreCase(parts.getBodyPart(i).getDisposition())) {
                epub = parts.getBodyPart(i).getInputStream().readAllBytes();
            }
        }
        assertNotNull(epub, "the mail has an attachment");

        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epub))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (name.endsWith(".xhtml") || name.endsWith(".html")) {
                    text.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertTrue(text.toString().contains(TITLE), "title in the content files");
        assertTrue(text.toString().contains(FIRST_PARAGRAPH), "first paragraph in the content files");
        assertTrue(text.toString().contains(SECOND_PARAGRAPH), "second paragraph in the content files");
    }

    @Test
    void withoutAKindleAddressTheSendIsRefusedAndTheReaderSeesTheSetupMessage() throws Exception {
        long userId = seedUser(null);
        long articleId = seedArticle(userId);
        MockHttpSession session = signIn();

        MvcResult refused = mockMvc.perform(post("/articles/{id}/send", articleId).session(session)
                        .with(csrf())
                        .header("User-Agent", KINDLE_UA))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/" + articleId))
                .andExpect(flash().attribute("error", "Add your Kindle e-mail address in Settings first"))
                .andReturn();

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(mailSender, never()).createMimeMessage();

        // The page the redirect lands on shows it.
        mockMvc.perform(get("/articles/{id}", articleId).session(session)
                        .header("User-Agent", KINDLE_UA)
                        .flashAttrs(refused.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Add your Kindle e-mail address in Settings first")));
    }
}
