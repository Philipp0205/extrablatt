package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Properties;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Feeds page has to report what a deployed instance was built from, so that a
 * VPS can be compared against the source.
 */
@WebMvcTest(controllers = AppController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.password=test-password-123",
        "app.kindle-email=kindle@example.com",
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key"
})
class BuildInfoViewTest {

    @TestConfiguration
    static class PackagedBuild {
        @Bean
        BuildProperties buildProperties() {
            Properties properties = new Properties();
            properties.setProperty("version", "1.0.0-SNAPSHOT");
            properties.setProperty("revision", "abc1234");
            properties.setProperty("time", "1767225600000");
            return new BuildProperties(properties);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

    @Test
    @WithMockUser(username = "kindle")
    void homePageReportsVersionRevisionAndBuildTime() throws Exception {
        when(feedService.listFeeds()).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1.0.0-SNAPSHOT")))
                .andExpect(content().string(containsString("abc1234")))
                .andExpect(content().string(containsString("2026-01-01 00:00 UTC")));
    }
}
