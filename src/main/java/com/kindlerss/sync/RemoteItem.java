package com.kindlerss.sync;

/** An article's identity and read state as the provider holds it. */
public record RemoteItem(long id, long feedId, String guid, String url, boolean read, long lastModified) {}
