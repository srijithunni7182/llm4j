package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/** Hot discussions about a topic, ranked by engagement — Hacker News via the Algolia search API. */
public class HackerNewsPulseTool extends PublicApiTool {

    public HackerNewsPulseTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "hn_pulse";
    }

    @Override
    public String getDescription() {
        return "Finds the most-discussed Hacker News stories about a topic (points + comment counts) — "
                + "great for contrarian angles and 'what people are arguing about'. Args: {\"query\": \"topic\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = arg(args, "query", "topic", "q");
        String url = "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=8&query=" + enc(query);
        Fetched fetched = fetch(url, "hn-search");

        StringBuilder out = new StringBuilder("Hacker News discussions for \"" + query + "\":\n");
        int n = 0;
        for (JsonNode hit : fetched.json().path("hits")) {
            String title = text(hit, "title");
            if (title.isBlank()) continue;
            out.append("- ").append(clip(title, 110))
               .append(" — ").append(hit.path("points").asInt()).append(" points, ")
               .append(hit.path("num_comments").asInt()).append(" comments\n");
            n++;
        }
        if (n == 0) out.append("- (no stories found — the topic is under-discussed, which can itself be an angle)\n");
        return out.append(fetched.sourceLine()).toString();
    }
}
