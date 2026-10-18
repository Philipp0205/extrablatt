package com.kindlerss.sync;

/** A subscription as the provider holds it. {@code category} is null at the root. */
public record RemoteFeed(long id, String url, String title, String category) {}
