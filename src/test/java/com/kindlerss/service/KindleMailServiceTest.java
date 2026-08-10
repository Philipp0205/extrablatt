package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KindleMailServiceTest {

    private JavaMailSender mailSender;
    private ArticleService articleService;
    private ArticleRepository articleRepository;
    private KindleMailService service;
    private Article article;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        articleService = mock(ArticleService.class);
        articleRepository = mock(ArticleRepository.class);
        AppProperties properties = new AppProperties(
                "password",
                "reader@kindle.com",
                "approved@example.com",
                "remember-key",
                null
        );
        service = new KindleMailService(
                mailSender,
                new EpubService(),
                articleService,
                articleRepository,
                properties
        );
        article = new Article(
                7L,
                2L,
                "guid",
                "Useful Article",
                "https://example.com/article",
                "Author",
                Instant.parse("2026-08-10T00:00:00Z"),
                "",
                "",
                "<p>Content</p>",
                false,
                null,
                Instant.now(),
                Instant.now(),
                "Example Feed"
        );
        when(articleRepository.findById(7L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(article, false)).thenReturn("<p>Content</p>");
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void sendsEpubBeforeRecordingDelivery() throws Exception {
        service.sendToKindle(7L, false);

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        assertEquals("Useful Article", message.getValue().getSubject());
        assertEquals("reader@kindle.com", message.getValue().getAllRecipients()[0].toString());
        assertTrue(message.getValue().getContentType().startsWith("multipart/"));
        verify(articleRepository).markSent(any(Long.class), any(Instant.class));
        verify(articleRepository).markRead(7L, true);
    }

    @Test
    void smtpFailureDoesNotRecordDelivery() {
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThrows(IllegalStateException.class, () -> service.sendToKindle(7L, false));

        verify(articleRepository, never()).markSent(any(Long.class), any(Instant.class));
        verify(articleRepository, never()).markRead(7L, true);
    }
}
