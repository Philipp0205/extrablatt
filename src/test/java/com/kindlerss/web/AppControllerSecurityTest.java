package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = AppController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.password=test-password-123",
        "app.kindle-email=kindle@example.com",
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key"
})
class AppControllerSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

    @Test
    void unauthenticatedRootRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginPageIsAccessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void formLoginSucceedsWithConfiguredPassword() throws Exception {
        mockMvc.perform(formLogin().user("kindle").password("test-password-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void homeRequiresAuthAndRenders() throws Exception {
        when(feedService.listFeeds()).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/refresh"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "kindle")
    void refreshWithCsrfWorks() throws Exception {
        doNothing().when(feedService).refreshAll();
        mockMvc.perform(post("/refresh").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        verify(feedService).refreshAll();
    }

    @Test
    @WithMockUser(username = "kindle")
    void missingArticleReturns404() throws Exception {
        when(articleService.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/articles/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "kindle")
    void itemsPageRenders() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds()).thenReturn(List.of());
        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(view().name("items"));
    }

    @Test
    void safeRedirectRejectsOpenRedirects() {
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("https://evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("//evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items?feed=1", AppController.safeRedirect("/items?feed=1"));
        org.junit.jupiter.api.Assertions.assertNull(AppController.safeHttpUrl("javascript:alert(1)"));
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/a", AppController.safeHttpUrl("https://example.com/a"));
    }
}
