package io.github.llm4j.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** A tool that allows agents to search the web using SerpAPI. */
public class SerpApiSearchTool implements Tool {

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public SerpApiSearchTool(String apiKey) {
        this(apiKey, new OkHttpClient(), "https://serpapi.com/search");
    }

    public SerpApiSearchTool(String apiKey, OkHttpClient httpClient) {
        this(apiKey, httpClient, "https://serpapi.com/search");
    }

    public SerpApiSearchTool(String apiKey, OkHttpClient httpClient, String baseUrl) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Useful for searching the web for current information, facts, and news. "
                + "Input should be a JSON object with a 'query' field, e.g., {\"query\": \"current population of Tokyo\"}.";
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

        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: SerpAPI key not configured for SerpApiSearchTool.";
        }

        return performSearch(query);
    }

    private String performSearch(String query) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        // Using engine=google by default
        String url =
                String.format("%s?q=%s&api_key=%s&engine=google", baseUrl, encodedQuery, apiKey);

        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody =
                        response.body() != null ? response.body().string() : "No error body";
                return "SerpAPI error (HTTP " + response.code() + "): " + errorBody;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            StringBuilder results = new StringBuilder();
            results.append("Search Results for '").append(query).append("':\n");
            boolean found = false;

            // 1. Top Stories (Very important for emerging situations)
            JsonNode topStories = root.get("top_stories");
            if (topStories != null && topStories.isArray() && topStories.size() > 0) {
                results.append("\nTOP STORIES:\n");
                for (int i = 0; i < Math.min(topStories.size(), 3); i++) {
                    JsonNode story = topStories.get(i);
                    results.append("- ").append(story.path("title").asText());
                    if (story.has("source"))
                        results.append(" (").append(story.get("source").asText()).append(")");
                    if (story.has("date")) results.append(" - ").append(story.get("date").asText());
                    results.append("\n  Link: ").append(story.path("link").asText()).append("\n");
                }
                found = true;
            }

            // 2. News Results
            JsonNode newsResults = root.get("news_results");
            if (newsResults != null && newsResults.isArray() && newsResults.size() > 0) {
                results.append("\nLATEST NEWS:\n");
                for (int i = 0; i < Math.min(newsResults.size(), 3); i++) {
                    JsonNode news = newsResults.get(i);
                    results.append("- ").append(news.path("title").asText());
                    if (news.has("source"))
                        results.append(" (").append(news.get("source").asText()).append(")");
                    if (news.has("date")) results.append(" - ").append(news.get("date").asText());
                    results.append("\n  ").append(news.path("snippet").asText()).append("\n");
                }
                found = true;
            }

            // 3. Knowledge Graph
            JsonNode kg = root.get("knowledge_graph");
            if (kg != null && !kg.isMissingNode()) {
                results.append("\nKNOWLEDGE GRAPH:\n");
                results.append("- ")
                        .append(kg.path("title").asText())
                        .append(": ")
                        .append(kg.path("description").asText())
                        .append("\n");
                found = true;
            }

            // 4. Organic Results (Items)
            JsonNode items = root.get("organic_results");
            if (items != null && items.isArray() && items.size() > 0) {
                results.append("\nWEB RESULTS:\n");
                for (int i = 0; i < Math.min(items.size(), 5); i++) {
                    JsonNode item = items.get(i);
                    String title = item.path("title").asText();
                    String snippet = item.path("snippet").asText();
                    String link = item.path("link").asText();

                    results.append(i + 1).append(". ").append(title).append("\n");
                    results.append("   - ").append(snippet).append("\n");
                    results.append("   - Link: ").append(link).append("\n");
                }
                found = true;
            }

            if (!found) {
                return "No search results found for '" + query + "' using SerpAPI.";
            }

            return results.toString();
        }
    }
}
