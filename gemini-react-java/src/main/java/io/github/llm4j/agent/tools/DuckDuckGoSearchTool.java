package io.github.llm4j.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A tool that allows agents to search the web using DuckDuckGo Instant Answer
 * API.
 * This is a free alternative, though less comprehensive than SerpAPI.
 */
public class DuckDuckGoSearchTool implements Tool {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DuckDuckGoSearchTool() {
        this(new OkHttpClient());
    }

    public DuckDuckGoSearchTool(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Useful for searching the web for current information, facts, and news. " +
                "Input should be a JSON object with a 'query' field, e.g., {\"query\": \"current population of Tokyo\"}. "
                +
                "This tool uses DuckDuckGo and is highly reliable.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        if (query == null || query.trim().isEmpty()) {
            query = (String) args.get("input");
        }

        if (query == null || query.trim().isEmpty()) {
            return "Error: No search 'query' provided.";
        }

        return performSearch(query);
    }

    private String performSearch(String query) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        // Using the Abstract Answer API (Instant Answer)
        String url = String.format("https://api.duckduckgo.com/?q=%s&format=json&pretty=1&no_html=1&skip_disambig=1",
                encodedQuery);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "llm4j-agent")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "DuckDuckGo API error (HTTP " + response.code() + ")";
            }

            JsonNode root = objectMapper.readTree(response.body().string());

            StringBuilder results = new StringBuilder();
            results.append("Search Results (DuckDuckGo) for '").append(query).append("':\n");

            String abstractText = root.path("AbstractText").asText();
            String abstractSource = root.path("AbstractSource").asText();
            String abstractURL = root.path("AbstractURL").asText();

            boolean foundSomething = false;

            if (abstractText != null && !abstractText.isEmpty()) {
                results.append("Primary Result: ").append(abstractText).append("\n");
                if (!abstractSource.isEmpty())
                    results.append("Source: ").append(abstractSource).append("\n");
                if (!abstractURL.isEmpty())
                    results.append("Link: ").append(abstractURL).append("\n\n");
                foundSomething = true;
            }

            JsonNode relatedTopics = root.path("RelatedTopics");
            if (relatedTopics.isArray() && relatedTopics.size() > 0) {
                results.append("Related Information:\n");
                int count = 0;
                for (JsonNode topic : relatedTopics) {
                    if (topic.has("Text")) {
                        results.append("- ").append(topic.get("Text").asText()).append("\n");
                        if (topic.has("FirstURL"))
                            results.append("  Link: ").append(topic.get("FirstURL").asText()).append("\n");
                        count++;
                    }
                    if (count >= 3)
                        break;
                }
                foundSomething = true;
            }

            if (!foundSomething) {
                return "No instant answer found for '" + query
                        + "' on DuckDuckGo. Try a more specific or common topic.";
            }

            return results.toString();
        }
    }
}
