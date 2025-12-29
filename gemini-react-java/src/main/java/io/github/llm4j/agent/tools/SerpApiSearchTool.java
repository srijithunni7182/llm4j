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
 * A tool that allows agents to search the web using SerpAPI.
 */
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
        return "Useful for searching the web for current information, facts, and news. " +
                "Input should be a JSON object with a 'query' field, e.g., {\"query\": \"current population of Tokyo\"}.";
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
        String url = String.format("%s?q=%s&api_key=%s&engine=google",
                baseUrl, encodedQuery, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                return "SerpAPI error (HTTP " + response.code() + "): " + errorBody;
            }

            JsonNode root = objectMapper.readTree(response.body().string());

            // SerpAPI returns results in 'organic_results'
            JsonNode items = root.get("organic_results");

            if (items == null || !items.isArray() || items.size() == 0) {
                return "No search results found for '" + query + "' using SerpAPI.";
            }

            StringBuilder results = new StringBuilder();
            results.append("Search Results for '").append(query).append("':\n");

            for (int i = 0; i < Math.min(items.size(), 5); i++) {
                JsonNode item = items.get(i);
                String title = item.path("title").asText();
                String snippet = item.path("snippet").asText();
                String link = item.path("link").asText();

                results.append(i + 1).append(". ").append(title).append("\n");
                results.append("   - ").append(snippet).append("\n");
                results.append("   - Link: ").append(link).append("\n\n");
            }

            return results.toString();
        }
    }
}
