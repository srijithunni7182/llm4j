package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/** What the world is reading right now — Wikipedia's "most read" feed (Wikimedia REST API). */
public class WikipediaTrendingTool extends PublicApiTool {

    public WikipediaTrendingTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "trending_now";
    }

    @Override
    public String getDescription() {
        return "Lists the most-read Wikipedia articles from yesterday with view counts — a live pulse of "
                + "what the internet is curious about. Args: {\"limit\": 10} (optional).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        int limit = parseLimit(arg(args, "limit"), 10);
        // The feed for "today" fills in during the day; yesterday (UTC) is always complete.
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        String url = String.format("https://en.wikipedia.org/api/rest_v1/feed/featured/%d/%02d/%02d",
                day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        Fetched fetched = fetch(url, "wikipedia-featured");

        StringBuilder out = new StringBuilder("Most-read on Wikipedia (" + day + "):\n");
        int n = 0;
        for (JsonNode article : fetched.json().path("mostread").path("articles")) {
            String title = article.path("titles").path("normalized").asText(text(article, "title"));
            if (title.startsWith("Main Page") || title.startsWith("Special:")) continue;
            out.append("- ").append(title)
               .append(" (").append(article.path("views").asLong()).append(" views)")
               .append(": ").append(clip(text(article, "description"), 90)).append('\n');
            if (++n >= limit) break;
        }
        if (n == 0) out.append("- (no articles returned)\n");
        return out.append(fetched.sourceLine()).toString();
    }

    static int parseLimit(String raw, int fallback) {
        try {
            return Math.max(1, Math.min(25, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
