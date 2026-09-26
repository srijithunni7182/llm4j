package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/** Hashtags trending across the fediverse right now — Mastodon's public trends API. */
public class MastodonTrendsTool extends PublicApiTool {

    public MastodonTrendsTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "trending_hashtags";
    }

    @Override
    public String getDescription() {
        return "Lists hashtags trending right now on mastodon.social with today's usage — use to ride "
                + "live conversations. Args: {} (none).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        Fetched fetched = fetch("https://mastodon.social/api/v1/trends/tags?limit=15", "mastodon-trends");

        StringBuilder out = new StringBuilder("Trending hashtags on Mastodon:\n");
        int n = 0;
        for (JsonNode tag : fetched.json()) {
            JsonNode today = tag.path("history").path(0);
            out.append("- #").append(text(tag, "name"))
               .append(" (").append(today.path("uses").asText("?")).append(" posts by ")
               .append(today.path("accounts").asText("?")).append(" people today)\n");
            n++;
        }
        if (n == 0) out.append("- (no trends returned)\n");
        return out.append(fetched.sourceLine()).toString();
    }
}
