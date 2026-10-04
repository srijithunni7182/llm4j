package io.github.llm4j.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** A tool that allows agents to search the web using Google Custom Search API. */
public class WebSearchTool implements Tool {

    private final SecretRef apiKey;
    private final String cx;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebSearchTool(String apiKey, String cx) {
        this(apiKey, cx, new OkHttpClient());
    }

    public WebSearchTool(String apiKey, String cx, OkHttpClient httpClient) {
        this(apiKey == null ? null : SecretRef.literal(apiKey), cx, httpClient, true);
    }

    private WebSearchTool(SecretRef apiKey, String cx, OkHttpClient httpClient, boolean unused) {
        this.apiKey = apiKey;
        this.cx = cx;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /** A tool whose key lives in a {@link io.github.llm4j.secret.SecretStore}: fetched for each search and not kept. */
    public static WebSearchTool withSecret(SecretRef apiKey, String cx) {
        return new WebSearchTool(apiKey, cx, new OkHttpClient(), true);
    }

    public static WebSearchTool withSecret(SecretRef apiKey, String cx, OkHttpClient httpClient) {
        return new WebSearchTool(apiKey, cx, httpClient, true);
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
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.trim().isEmpty()) {
            query = (String) args.get("input");
        }

        if (query == null || query.trim().isEmpty()) {
            return "Error: No search 'query' provided.";
        }

        if (apiKey == null || (apiKey.isLiteral() ? apiKey.resolve().isEmpty() : !apiKey.exists())) {
            return "Error: Google API key not configured for WebSearchTool"
                    + (apiKey != null && !apiKey.isLiteral() ? " (" + apiKey + " is not in the store)." : ".");
        }

        if (cx == null || cx.isEmpty()) {
            return "Error: Google Custom Search CX (Search Engine ID) not configured. "
                    + "Please set the GOOGLE_SEARCH_CX environment variable or configure it in AgentConfiguration.";
        }

        try {
            return performSearch(query);
        } catch (SecretException e) {
            return "Error: Google API key unavailable: " + e.getMessage();
        } catch (IOException e) {
            return "Error performing search: " + e.getMessage();
        }
    }

    private String performSearch(String query) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String key = apiKey.resolveFor("www.googleapis.com");
        String url =
                String.format(
                        "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s",
                        URLEncoder.encode(key, StandardCharsets.UTF_8), cx, encodedQuery);

        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody =
                        response.body() != null ? response.body().string() : "No error body";
                return "Search API error (HTTP " + response.code() + "): " + errorBody.replace(key, "***");
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode items = root.get("items");

            if (items == null || !items.isArray() || items.size() == 0) {
                return "No search results found for '" + query + "'.";
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

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return io.github.llm4j.model.ToolSchema.object().string("query", "What to search the web for", true).build();
    }
}
