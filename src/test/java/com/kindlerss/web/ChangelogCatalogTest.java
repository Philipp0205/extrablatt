package com.kindlerss.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogCatalogTest {

    @Test
    void packagedChangelogListsNewestReleaseFirst() {
        ChangelogCatalog catalog = ChangelogCatalog.instance();
        assertFalse(catalog.releases().isEmpty());
        assertEquals(catalog.releases().get(0).id(), catalog.latestId().orElseThrow());
        assertFalse(catalog.latest().orElseThrow().popupItems().isEmpty());
    }

    @Test
    void unseenNoticeIsTheLatestReleaseUntilThatIdIsAcknowledged() {
        ChangelogCatalog catalog = new ChangelogCatalog(List.of(
                new ChangelogCatalog.Release("new", "today", "New", List.of("highlight"), List.of("full")),
                new ChangelogCatalog.Release("old", "yesterday", "Old", List.of(), List.of("earlier"))
        ));

        assertEquals("new", catalog.unseenSince(null).orElseThrow().id());
        assertEquals("new", catalog.unseenSince("old").orElseThrow().id());
        assertTrue(catalog.unseenSince("new").isEmpty());
    }

    @Test
    void popupFallsBackToTheFullChangeListWhenHighlightsAreMissing() {
        ChangelogCatalog.Release release = new ChangelogCatalog.Release(
                "id", "today", "Title", List.of(), List.of("only this"));
        assertEquals(List.of("only this"), release.popupItems());
    }
}
