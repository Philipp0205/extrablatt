package com.kindlerss.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Publishes the build identity of the running instance to every view, so a
 * deployment can be checked against the source it was built from. The values come
 * from {@code META-INF/build-info.properties}, written by the Spring Boot Maven
 * plugin; the revision is passed in at build time (see Dockerfile).
 */
@ControllerAdvice
public class BuildInfoAdvice {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoAdvice.class);
    private static final String UNKNOWN = "unknown";
    private static final DateTimeFormatter BUILT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Version version;

    public BuildInfoAdvice(ObjectProvider<BuildProperties> buildProperties) {
        this.version = describe(buildProperties.getIfAvailable());
        log.info("Kindle RSS {} (revision {}, built {})",
                version.number(), version.revision(), version.builtAt());
    }

    @ModelAttribute("appVersion")
    public Version version() {
        return version;
    }

    static Version describe(BuildProperties build) {
        // Absent when the app runs from classes that were not packaged by Maven,
        // for example straight out of an IDE.
        if (build == null) {
            return new Version("development build", UNKNOWN, UNKNOWN);
        }
        Instant time = build.getTime();
        return new Version(
                text(build.getVersion(), "development build"),
                text(build.get("revision"), UNKNOWN),
                time == null ? UNKNOWN : BUILT_AT.format(time));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Version(String number, String revision, String builtAt) {
    }
}
