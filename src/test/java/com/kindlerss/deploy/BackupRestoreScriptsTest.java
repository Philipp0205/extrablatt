package com.kindlerss.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs deploy/backup.sh and deploy/restore.sh against a stand-in {@code docker}
 * that records what it was asked to do. The scripts run in a copy of deploy/ so
 * that the .env they read and the backups they write stay in a temp directory.
 */
class BackupRestoreScriptsTest {

    @TempDir
    Path tmp;

    private Path deployDir;
    private Path bin;
    private Path dockerLog;
    private Path psqlInput;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
                "needs bash");
        Path repo = tmp.resolve("repo");
        deployDir = repo.resolve("deploy");
        Files.createDirectories(deployDir);
        for (String name : List.of("backup.sh", "restore.sh", "env-file.sh", "docker-compose.yml")) {
            Files.copy(Path.of("deploy", name), deployDir.resolve(name));
        }
        bin = Files.createDirectories(tmp.resolve("bin"));
        dockerLog = tmp.resolve("docker.log");
        psqlInput = tmp.resolve("psql.in");
        Path docker = bin.resolve("docker");
        Files.writeString(docker, """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$DOCKER_LOG"
                case "$*" in
                  *pg_dump*) printf 'SELECT 1;\\n' ;;
                  *psql*) cat > "$PSQL_INPUT" ;;
                esac
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void backupReadsOnlyTheDatabaseSettingsFromEnvAndNeverRunsIt() throws Exception {
        Path marker = tmp.resolve("sourced");
        Files.writeString(deployDir.getParent().resolve(".env"), """
                POSTGRES_DB="extrablatt_db"
                export POSTGRES_USER=reader # the app role
                SMTP_PASSWORD=$(touch %s)
                USER=should-not-leak
                """.formatted(marker));

        Result result = run("backup.sh", List.of(), Map.of());

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(marker), ".env must not be executed");
        String calls = Files.readString(dockerLog);
        assertTrue(calls.contains("pg_dump -U reader -d extrablatt_db"), calls);
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertTrue(backups.get(0).getFileName().toString().endsWith(".sql.gz"));
        assertEquals("SELECT 1;\n", gunzip(Files.readAllBytes(backups.get(0))));
    }

    @Test
    void backupKeepsOnlyTheNewestN() throws Exception {
        Path out = Files.createDirectories(deployDir.resolve("backups"));
        for (String stamp : List.of("20260101T000000Z", "20260102T000000Z", "20260103T000000Z")) {
            Files.writeString(out.resolve("kindle_rss_" + stamp + ".sql.gz"), "old");
        }
        Path unrelated = Files.writeString(out.resolve("notes.txt"), "keep me");

        Result result = run("backup.sh", List.of(), Map.of("BACKUP_KEEP", "2"));

        assertEquals(0, result.exit(), result.output());
        List<String> names = backups().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(2, names.size(), names.toString());
        assertTrue(names.contains("kindle_rss_20260103T000000Z.sql.gz"), names.toString());
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void backupRejectsANonNumericKeepCount() throws Exception {
        Result result = run("backup.sh", List.of(), Map.of("BACKUP_KEEP", "lots"));

        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("BACKUP_KEEP"), result.output());
    }

    @Test
    void backupEncryptsWhenARecipientIsSet() throws Exception {
        Path gpg = bin.resolve("gpg");
        Files.writeString(gpg, """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$DOCKER_LOG"
                printf 'ENCRYPTED:'
                cat
                """);
        Files.setPosixFilePermissions(gpg, PosixFilePermissions.fromString("rwxr-xr-x"));

        Result result = run("backup.sh", List.of(), Map.of("BACKUP_GPG_RECIPIENT", "ops@example.com"));

        assertEquals(0, result.exit(), result.output());
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertTrue(backups.get(0).getFileName().toString().endsWith(".sql.gz.gpg"));
        assertTrue(new String(Files.readAllBytes(backups.get(0)), StandardCharsets.ISO_8859_1)
                .startsWith("ENCRYPTED:"));
        assertTrue(Files.readString(dockerLog).contains("--recipient ops@example.com"));
    }

    @Test
    void restoreWithoutConfirmationChangesNothing() throws Exception {
        Path dump = gzipFile("SELECT 2;\n");

        Result result = run("restore.sh", List.of(dump.toString()), Map.of());

        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("--yes"), result.output());
        assertFalse(Files.exists(dockerLog), "docker must not be called");
    }

    @Test
    void restoreWithYesRunsInOneTransactionAndStopsOnError() throws Exception {
        Path dump = gzipFile("SELECT 2;\n");

        Result result = run("restore.sh", List.of("--yes", dump.toString()), Map.of());

        assertEquals(0, result.exit(), result.output());
        String calls = Files.readString(dockerLog);
        assertTrue(calls.contains("psql -U kindle -d kindle_rss"), calls);
        assertTrue(calls.contains("ON_ERROR_STOP=1"), calls);
        assertTrue(calls.contains("--single-transaction"), calls);
        // --single-transaction is only honoured with -c/-f, so stdin must be read via -f -
        assertTrue(calls.contains("-f -"), calls);
        assertEquals("SELECT 2;\n", Files.readString(psqlInput));
    }

    @Test
    void restoreRefusesAMissingFile() throws Exception {
        Result result = run("restore.sh", List.of("--yes", tmp.resolve("nope.sql.gz").toString()), Map.of());

        assertNotEquals(0, result.exit());
        assertFalse(Files.exists(dockerLog));
    }

    private List<Path> backups() throws IOException {
        Path out = deployDir.resolve("backups");
        try (Stream<Path> files = Files.list(out)) {
            return files.filter(p -> p.getFileName().toString().startsWith("kindle_rss_")).sorted().toList();
        }
    }

    private Path gzipFile(String content) throws IOException {
        Path file = tmp.resolve("dump.sql.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Result run(String script, List<String> args, Map<String, String> env) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", deployDir.resolve(script).toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        for (String key : List.of("POSTGRES_DB", "POSTGRES_USER", "BACKUP_KEEP", "BACKUP_AGE_RECIPIENT",
                "BACKUP_GPG_RECIPIENT", "BACKUP_AGE_IDENTITY", "ENV_FILE")) {
            environment.remove(key);
        }
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        environment.put("DOCKER_LOG", dockerLog.toString());
        environment.put("PSQL_INPUT", psqlInput.toString());
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
