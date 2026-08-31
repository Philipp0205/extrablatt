package com.kindlerss.domain;

/**
 * What an account is entitled to. The gate sits on Kindle delivery because that
 * is the only thing with a real unit cost — one article sent is one e-mail —
 * while reading in the browser is a database row either way.
 */
public enum Plan {

    /** Enough to read with every day, small enough to give away indefinitely. */
    FREE,

    /** The paid plan: the full {@code app.limits.*} allowances plus newsletters. */
    SUPPORTER
}
