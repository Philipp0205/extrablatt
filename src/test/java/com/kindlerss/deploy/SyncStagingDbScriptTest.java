package com.kindlerss.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs deploy/sync-staging-db.sh against a stand-in {@code railway} CLI. The
 * real SQL is covered by {@link StagingScrubTest}; this checks the plumbing:
 * what gets streamed to staging, the SMTP preflight, and that the operator's
 * ~/.ssh is left alone.
 */
class SyncStagingDbScriptTest {

    private static final String COMPLETE_DUMP = """
            SET search_path = '';
            CREATE TABLE public.users ();
            -- PostgreSQL database dump complete
            """;

    @TempDir
    Path tmp;

    private Path bin;
    private Path home;
    private Path log;
    private Path restored;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/ssh-keygen")) && Files.isExecutable(Path.of("/usr/bin/ssh-agent")),
                "needs OpenSSH client tools");
        bin = Files.createDirectories(tmp.resolve("bin"));
        home = Files.createDirectories(tmp.resolve("home"));
        log = tmp.resolve("railway.log");
        restored = tmp.resolve("restored.sql");
        Files.writeString(tmp.resolve("vars-production"), "SMTP_HOST=smtp.resend.com\nSMTP_USERNAME=resend\n"
                + "SMTP_PASSWORD=re_prod\nREMEMBER_ME_KEY=prod-key\n");
        Files.writeString(tmp.resolve("vars-staging"), "SMTP_HOST=localhost\nSMTP_USERNAME=sink\n"
                + "SMTP_PASSWORD=sink\nREMEMBER_ME_KEY=staging-key\n");
        Files.writeString(tmp.resolve("dump"), COMPLETE_DUMP);
        Path railway = bin.resolve("railway");
        Files.writeString(railway, """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$FAKE_LOG"
                env_name=""
                prev=""
                for a in "$@"; do
                  [[ "$prev" == "--environment" ]] && env_name="$a"
                  prev="$a"
                done
                case "$*" in
                  "variable list"*)
                    cat "$FAKE_DIR/vars-$env_name" ;;
                  "ssh keys add"*)
                    ssh-add -l >> "$FAKE_LOG" ;;
                  "ssh keys remove"*) ;;
                  ssh*pg_dump*)
                    echo "ssh-binary $(command -v ssh)" >> "$FAKE_LOG"
                    cat "$FAKE_DIR/dump" ;;
                  ssh*"-f -"*)
                    cat > "$FAKE_RESTORED" ;;
                  ssh*) ;;
                esac
                """);
        Files.setPosixFilePermissions(railway, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void streamsKeepListDumpAndScrubIntoOneTransaction() throws Exception {
        Result result = run(Map.of("STAGING_KEEP_EMAILS", "tester@example.org, admin@example.com"));

        assertEquals(0, result.exit(), result.output());
        String sql = Files.readString(restored);
        int keep = sql.indexOf("INSERT INTO pg_temp.staging_keep_emails VALUES ('tester@example.org');");
        int dump = sql.indexOf("CREATE TABLE public.users");
        int scrub = sql.indexOf("DELETE FROM email_tokens;");
        assertTrue(keep >= 0 && keep < dump && dump < scrub, sql);
        assertTrue(sql.contains("('admin@example.com')"), sql);
        String calls = Files.readString(log);
        assertTrue(calls.contains("psql -d railway -v ON_ERROR_STOP=1 --single-transaction --quiet -f -"), calls);
        assertFalse(calls.contains("-- --"), "a second -- would reach the remote shell: " + calls);
    }

    @Test
    void registersAThrowawayKeyOutsideHomeAndRemovesIt() throws Exception {
        Result result = run(Map.of());

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(home.resolve(".ssh")), "~/.ssh must not be touched");
        String calls = Files.readString(log);
        assertTrue(calls.contains("extrablatt-staging-sync"), "key was not in the private agent: " + calls);
        assertTrue(calls.matches("(?s).*ssh keys remove extrablatt-staging-sync-\\S+.*"), calls);
        assertTrue(calls.matches("(?s).*--identity-file \\S+/id_ed25519.*"), calls);
        assertFalse(calls.contains("ssh-binary /usr/bin/ssh"), "railway must run the wrapped ssh: " + calls);
        try (var leftovers = Files.list(tmp.resolve("work"))) {
            assertEquals(0, leftovers.count(), "temp key and config must be removed");
        }
    }

    @Test
    void refusesWhenStagingSendsWithProductionSmtp() throws Exception {
        Files.copy(tmp.resolve("vars-production"), tmp.resolve("vars-staging"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Result result = run(Map.of());

        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("same SMTP credentials"), result.output());
        assertFalse(result.output().contains("re_prod"), "secrets must not be printed");
        assertFalse(Files.exists(restored));
    }

    @Test
    void sharedSmtpCanBeAllowedExplicitly() throws Exception {
        Files.copy(tmp.resolve("vars-production"), tmp.resolve("vars-staging"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Result result = run(Map.of("ALLOW_SHARED_SMTP", "1"));

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("REMEMBER_ME_KEY"), result.output());
        assertTrue(Files.exists(restored));
    }

    @Test
    void anIncompleteDumpLeavesStagingAlone() throws Exception {
        Files.writeString(tmp.resolve("dump"), "SET search_path = '';\nCREATE TABLE public.us");

        Result result = run(Map.of());

        assertNotEquals(0, result.exit());
        assertFalse(Files.exists(restored));
    }

    @Test
    void rejectsAKeepEntryThatIsNotAnAddress() throws Exception {
        Result result = run(Map.of("STAGING_KEEP_EMAILS", "x@example.com'); DROP TABLE users; --"));

        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("STAGING_KEEP_EMAILS"), result.output());
        assertFalse(Files.exists(log));
    }

    @Test
    void refusesAProjectTokenBecauseItCannotManageKeys() throws Exception {
        Result result = run(Map.of("RAILWAY_TOKEN", "project-token"));

        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("RAILWAY_API_TOKEN"), result.output());
        assertFalse(Files.exists(restored));
    }

    private Result run(Map<String, String> env) throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        ProcessBuilder builder = new ProcessBuilder("bash", Path.of("deploy/sync-staging-db.sh").toString())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        for (String key : List.of("RAILWAY_TOKEN", "RAILWAY_API_TOKEN", "RAILWAY_PROJECT_ID", "STAGING_KEEP_EMAILS",
                "ALLOW_SHARED_SMTP", "SSH_KEY_PATH", "RAILWAY_SSH_KNOWN_HOSTS", "SSH_AUTH_SOCK", "GITHUB_RUN_ID")) {
            environment.remove(key);
        }
        environment.put("HOME", home.toString());
        environment.put("TMPDIR", work.toString());
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        environment.put("FAKE_LOG", log.toString());
        environment.put("FAKE_DIR", tmp.toString());
        environment.put("FAKE_RESTORED", restored.toString());
        environment.putAll(env);
        Process process = builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile())).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "script timed out");
        return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String output) {
    }
}
