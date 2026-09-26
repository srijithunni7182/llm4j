package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/**
 * Grounds claims before they go viral — Wikipedia search + page summary (Wikimedia REST API).
 * Viral content that is wrong gets ratioed; agents use this to verify facts they cite.
 */
public class WikipediaFactCheckTool extends PublicApiTool {

    public WikipediaFactCheckTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "fact_check";
    }

    @Override
    public String getDescription() {
        return "Looks a subject up on Wikipedia and returns a short factual summary with its source URL. "
                + "Use before stating stats or facts. Args: {\"query\": \"subject or claim\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = arg(args, "query", "subject", "claim", "q");
        Fetched search = fetch("https://en.wikipedia.org/w/rest.php/v1/search/page?limit=3&q=" + enc(query),
                "wikipedia-search");
        JsonNode first = search.json().path("pages").path(0);
        if (first.isMissingNode()) {
            return "No Wikipedia article found for \"" + query + "\". Avoid stating specific facts about it.\n"
                    + search.sourceLine();
        }
        String key = text(first, "key");
        Fetched summary = fetch("https://en.wikipedia.org/api/rest_v1/page/summary/" + enc(key), "wikipedia-summary");
        JsonNode page = summary.json();
        String title = text(page, "title").isBlank() ? text(first, "title") : text(page, "title");
        String extract = text(page, "extract").isBlank() ? text(first, "excerpt") : text(page, "extract");
        String link = page.path("content_urls").path("desktop").path("page").asText(
                "https://en.wikipedia.org/wiki/" + key);
        return "Wikipedia — " + title + ": " + clip(extract.replaceAll("<[^>]+>", ""), 600)
                + "\nSource: " + link + "\n" + summary.sourceLine();
    }
}
