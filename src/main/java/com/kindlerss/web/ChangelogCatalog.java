package com.kindlerss.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Release notes shipped with the app. The JSON file is the source of truth for
 * both the Settings changelog and the one-time "what's new" notice.
 */
public final class ChangelogCatalog {

    private static final ChangelogCatalog INSTANCE = loadFromClasspath();

    private final List<Release> releases;

    ChangelogCatalog(List<Release> releases) {
        this.releases = releases == null ? List.of() : List.copyOf(releases);
    }

    public static ChangelogCatalog instance() {
        return INSTANCE;
    }

    static ChangelogCatalog loadFromClasspath() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ChangelogCatalog.class.getResourceAsStream("/changelog.json")) {
            if (in == null) {
                throw new IllegalStateException("changelog.json is missing from the classpath");
            }
            File file = mapper.readValue(in, File.class);
            return new ChangelogCatalog(file.releases());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read changelog.json", e);
        }
    }

    public List<Release> releases() {
        return releases;
    }

    public Optional<Release> latest() {
        return releases.isEmpty() ? Optional.empty() : Optional.of(releases.get(0));
    }

    public Optional<String> latestId() {
        return latest().map(Release::id);
    }

    /**
     * The newest release the account has not acknowledged. An empty last-seen
     * value means they have never dismissed the notice, so the latest release
     * is shown once.
     */
    public Optional<Release> unseenSince(String lastSeenId) {
        Optional<Release> newest = latest();
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        if (newest.get().id().equals(lastSeenId)) {
            return Optional.empty();
        }
        return newest;
    }

    public record File(List<Release> releases) {
    }

    public record Release(
            String id,
            String date,
            String title,
            List<String> highlights,
            List<String> changes
    ) {
        public Release {
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        /** Lines shown in the new-release popup. */
        public List<String> popupItems() {
            return highlights.isEmpty() ? changes : highlights;
        }
    }
}
