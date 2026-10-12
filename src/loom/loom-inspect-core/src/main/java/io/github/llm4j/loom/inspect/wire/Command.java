package io.github.llm4j.loom.inspect.wire;

/** An operator's request for one run, queued at the server for the process that owns it. It lives {@link #TTL_SECONDS} seconds; an old one is never carried out. */
public record Command(String id, String trace, String action, String by, String issued, String expires) {

    public static final long TTL_SECONDS = 60;
}
