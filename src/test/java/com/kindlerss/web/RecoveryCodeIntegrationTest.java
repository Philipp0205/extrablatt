package com.kindlerss.web;

import com.kindlerss.repository.UserRepository;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.service.DataExportService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.RecoveryCodeService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recovery codes against the whole application and a real database. */
@SpringBootTest
@AutoConfigureMockMvc
class RecoveryCodeIntegrationTest {

    private static final Pattern CODE = Pattern.compile(
            "[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}(?:-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}){2}");

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
        registry.add("app.remember-me-key", () -> "recovery-it-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RecoveryCodeService recoveryCodes;
    @Autowired DataExportService dataExportService;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private long userId;

    @BeforeEach
    void seedAccount() {
        userId = users.insert("rc-" + UUID.randomUUID() + "@example.com", "hash").id();
        users.markEmailVerified(userId);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor signedIn() {
        return user(new AppUserDetails(users.findById(userId).orElseThrow()));
    }

    private String storedHash() {
        return jdbc.queryForObject("SELECT recovery_hash FROM users WHERE id = ?", String.class, userId);
    }

    private MvcResult postRecoveryCode() throws Exception {
        return mockMvc.perform(post("/settings/account/recovery-code").with(signedIn()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/account"))
                .andReturn();
    }

    private String shownCode(MvcResult posted) throws Exception {
        String page = mockMvc.perform(get("/settings/account").with(signedIn()).flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = CODE.matcher(page.substring(page.indexOf("Recovery code")));
        assertTrue(matcher.find(), "the page should show the code");
        return matcher.group();
    }

    @Test
    void generatingStoresOnlyTheHashOfAWellFormedCode() throws Exception {
        assertNull(storedHash());

        String code = shownCode(postRecoveryCode());

        assertTrue(CODE.matcher(code).matches(), code);
        String stored = storedHash();
        assertNotNull(stored);
        assertNotEquals(code, stored);
        assertEquals(sha256Hex(code), stored);
        assertEquals(stored, RecoveryCodeService.hash(code));
    }

    @Test
    void rotatingReplacesTheHashSoTheOldCodeNoLongerMatches() throws Exception {
        String first = shownCode(postRecoveryCode());
        String firstHash = storedHash();

        String second = shownCode(postRecoveryCode());
        String secondHash = storedHash();

        assertNotEquals(first, second);
        assertNotEquals(firstHash, secondHash);
        assertEquals(sha256Hex(second), secondHash);
        assertNotEquals(RecoveryCodeService.hash(first), secondHash);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE recovery_hash = ?",
                Integer.class, RecoveryCodeService.hash(first)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE recovery_hash = ?",
                Integer.class, RecoveryCodeService.hash(second)));
    }

    @Test
    void thePageShowsTheCodeOnceAndNotOnTheNextVisit() throws Exception {
        MvcResult posted = postRecoveryCode();
        assertFalse(posted.getResponse().getRedirectedUrl().matches(".*[A-Z0-9]{4}-[A-Z0-9]{4}.*"));

        String code = shownCode(posted);

        mockMvc.perform(get("/settings/account").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(code))))
                .andExpect(content().string(not(containsString("will not be shown again"))))
                .andExpect(content().string(containsString("Replace recovery code")));
    }

    @Test
    void thePageOffersCreateUntilThereIsACode() throws Exception {
        mockMvc.perform(get("/settings/account").with(signedIn()))
                .andExpect(content().string(containsString("Create recovery code")))
                .andExpect(content().string(not(containsString("Replace recovery code"))));
        assertFalse(recoveryCodes.hasCode(userId));

        postRecoveryCode();

        assertTrue(recoveryCodes.hasCode(userId));
    }

    @Test
    void thePlainCodeIsNotAnywhereInTheDatabase() throws Exception {
        String code = shownCode(postRecoveryCode());

        List<String> rows = jdbc.query("SELECT u::text FROM users u WHERE id = ?",
                (rs, n) -> rs.getString(1), userId);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).contains(code), rows.get(0));
        assertFalse(rows.get(0).contains(code.replace("-", "")), rows.get(0));
    }

    @Test
    void theDataExportDoesNotIncludeTheRecoveryHash() throws Exception {
        String code = shownCode(postRecoveryCode());
        String hash = storedHash();

        String export = new String(dataExportService.exportJson(userId), StandardCharsets.UTF_8);

        assertFalse(export.contains(hash), export);
        assertFalse(export.contains("recovery"), export);
        assertFalse(export.contains(code), export);
    }

    @Test
    void creatingNeedsCsrfAndASignedInAccount() throws Exception {
        mockMvc.perform(post("/settings/account/recovery-code").with(signedIn()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/settings/account/recovery-code").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertNull(storedHash());
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
