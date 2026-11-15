package com.kindlerss.domain;

/** Where a feed's articles come from. */
public enum FeedSource {
    /** Polled over HTTP on a schedule, as RSS/Atom. */
    RSS,
    /** Created by the retired newsletter inbox; kept so existing feeds load. Never polled. */
    NEWSLETTER,
    /** Pages fetched once from a pasted URL, not polled afterwards. */
    CLIPPING
}
