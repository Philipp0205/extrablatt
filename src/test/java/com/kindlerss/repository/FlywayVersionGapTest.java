package com.kindlerss.repository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staging applied V11 from a branch that skipped V10. The next deploy then
 * shipped V10 again, and Flyway would not start.
 */
class FlywayVersionGapTest {

    @Test
    void appliesTheSkippedVersionOnceOutOfOrderIsOn() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            Path gapDir = Files.createTempDirectory("flyway-gap");
            copyMigrationsExcept(Path.of("src/main/resources/db/migration"), gapDir, "V10__last_seen_changelog.sql");

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("filesystem:" + gapDir.toAbsolutePath())
                    .load()
                    .migrate();

            assertEquals(0, changelogColumnCount(dataSource));

            Flyway inOrder = Flyway.configure().dataSource(dataSource).load();
            FlywayValidateException failure = assertThrows(FlywayValidateException.class, inOrder::migrate);
            assertTrue(failure.getMessage().contains("10"), failure.getMessage());

            Flyway.configure()
                    .dataSource(dataSource)
                    .outOfOrder(true)
                    .load()
                    .migrate();

            assertEquals(1, changelogColumnCount(dataSource));
        }
    }

    private static int changelogColumnCount(DataSource dataSource) {
        Integer count = new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'users' AND column_name = 'last_seen_changelog_id'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private static void copyMigrationsExcept(Path source, Path target, String skipFile) throws Exception {
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                if (skipFile.equals(file.getFileName().toString())) {
                    continue;
                }
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
