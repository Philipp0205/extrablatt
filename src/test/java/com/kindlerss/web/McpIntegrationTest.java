package com.kindlerss.web;

import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.ApiTokenService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The MCP endpoint against the whole application and a real database; no network, no mail. */
@SpringBootTest
@AutoConfigureMockMvc
class McpIntegrationTest {

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
        registry.add("app.remember-me-key", () -> "mcp-it-remember-key");
        registry.add("app.articles.page-size", () -> "5");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;
    @Autowired ApiTokenService tokenService;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private long userA;
    private long userB;
    private String tokenA;

    @BeforeEach
    void seedTwoAccounts() {
        userA = verifiedUser("a-" + UUID.randomUUID() + "@example.com");
        userB = verifiedUser("b-" + UUID.randomUUID() + "@example.com");
        tokenA = tokenService.create(userA, "A").secret();
    }

    private long verifiedUser(String email) {
        long id = users.insert(email, "hash").id();
        users.markEmailVerified(id);
        return id;
    }

    private long article(Feed feed, String guid) {
        return articles.insert(feed.id(), guid, "Title " + guid, "https://example.com/" + guid, "Ann",
                Instant.now().minus(1, ChronoUnit.MINUTES), "<p>Summary " + guid + "</p>", null);
    }

    private org.springframework.test.web.servlet.ResultActions rpc(String token, String body) throws Exception {
        var request = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions call(String token, String tool, String arguments)
            throws Exception {
        return rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}");
    }

    @Test
    void noTokenOrABadTokenIsA401WithJson() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        rpc(null, list)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        rpc("eb_notatoken", list).andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedTokenIsRefused() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM api_tokens WHERE user_id = ?", Long.class, userA);
        tokenService.revoke(userA, id);

        rpc(tokenA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenListsTheTools() throws Exception {
        rpc(tokenA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.result.tools[*].name").value(hasItems(
                        "list_feeds", "list_categories", "follow_feed", "unfollow_feed", "list_items",
                        "list_unread", "get_article", "mark_read", "mark_all_read", "send_to_kindle",
                        "send_url_to_kindle", "get_account")));
    }

    @Test
    void toolsReadTheTokensOwnAccountAndData() throws Exception {
        Feed feed = feeds.insert(userA, "Tech blog", "https://a.example/feed", "https://a.example", "Tech");
        long id = article(feed, "a1");
        Feed foreign = feeds.insert(userB, "Secret", "https://b.example/feed", "https://b.example", "Private");
        article(foreign, "b1");

        call(tokenA, "get_account", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"emailVerified\":true")))
                .andExpect(content().string(not(containsString(tokenA))));
        call(tokenA, "list_feeds", "{}")
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("Tech blog")))
                .andExpect(content().string(not(containsString("Secret"))));
        call(tokenA, "list_unread", "{}")
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("Title a1")))
                .andExpect(content().string(not(containsString("Title b1"))));
        call(tokenA, "mark_read", "{\"id\":" + id + "}")
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"read\":true")));
        call(tokenA, "list_unread", "{}")
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"total\":0")));
    }

    @Test
    void anotherAccountsItemsAreNotFound() throws Exception {
        Feed foreign = feeds.insert(userB, "Secret", "https://b.example/feed", "https://b.example", null);
        long articleB = article(foreign, "b2");

        call(tokenA, "get_article", "{\"id\":" + articleB + "}")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("Article not found"));
        call(tokenA, "list_items", "{\"feed\":" + foreign.id() + "}")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("Feed not found"));
        call(tokenA, "list_items", "{}")
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"total\":0")));
    }

    @Test
    void sendingGoesThroughTheKindleServiceAndFailuresAreShortSentences() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://h.example/feed", "https://h.example", null);
        long id = article(feed, "h1");

        call(tokenA, "send_to_kindle", "{\"id\":" + id + ",\"images\":true}")
                .andExpect(jsonPath("$.result.isError").value(false));
        verify(kindleMailService).sendToKindle(userA, id, true);

        doThrow(new IllegalStateException("smtp password=hunter2"))
                .when(kindleMailService).sendToKindle(userA, id, false);
        call(tokenA, "send_to_kindle", "{\"id\":" + id + "}")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("Could not send article"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void theWebsiteStillRedirectsToLoginAndAMcpTokenDoesNotSignInToIt() throws Exception {
        mockMvc.perform(get("/settings").header("Authorization", "Bearer " + tokenA)).andExpect(status().is3xxRedirection());
    }
}
