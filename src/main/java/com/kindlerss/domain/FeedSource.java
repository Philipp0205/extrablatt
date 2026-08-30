package com.kindlerss.domain;

/** Where a feed's articles come from. */
public enum FeedSource {
    /** Polled over HTTP on a schedule, as RSS/Atom. */
    RSS,
    /** Delivered by e-mail to a per-feed inbound address and stored as they arrive. */
    NEWSLETTER,
    /** Pages fetched once from a pasted URL, not polled afterwards. */
    CLIPPING
}
