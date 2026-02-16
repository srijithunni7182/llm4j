package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * A tool that allows agents to search the web using DuckDuckGo Lite. This provides actual search
 * results for news and current events, unlike the limited Instant Answer API.
 */
public class DuckDuckGoSearchTool implements Tool {

    private final OkHttpClient httpClient;
    private final String baseUrl;

    public DuckDuckGoSearchTool() {
        this(new OkHttpClient(), "https://duckduckgo.com/lite/");
    }

    public DuckDuckGoSearchTool(OkHttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Useful for searching the web for current information, facts, and news. "
                + "Input should be a JSON object with a 'query' field, e.g., {\"query\": \"current population of Tokyo\"}. "
                + "This tool is highly reliable for latest news and emerging situations.";
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
        String url = String.format("%s?q=%s", baseUrl, encodedQuery);

        Request request =
                new Request.Builder()
                        .url(url)
                        .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: DuckDuckGo Lite returned HTTP " + response.code();
            }

            String html = response.body().string();
            Document doc = Jsoup.parse(html);

            // DDG Lite structure:
            // Titles are in <a class="result-link">
            // Snippets are in <td class="result-snippet">
            Elements resultLinks = doc.select("a.result-link");
            Elements snippets = doc.select("td.result-snippet");

            if (resultLinks.isEmpty()) {
                return "Error: No search results found for '" + query + "' on DuckDuckGo Lite.";
            }

            StringBuilder results = new StringBuilder();
            results.append("Search Results (DuckDuckGo Lite) for '").append(query).append("':\n\n");

            int count = Math.min(resultLinks.size(), 5);
            for (int i = 0; i < count; i++) {
                Element link = resultLinks.get(i);
                String title = link.text();
                String href = link.attr("href");

                // Clean up the href if it's a redirect
                if (href.contains("uddg=")) {
                    try {
                        String[] parts = href.split("uddg=");
                        if (parts.length > 1) {
                            String encoded = parts[1].split("&")[0];
                            href = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        // Keep as is if cleaning fails
                    }
                }

                String snippet =
                        (i < snippets.size()) ? snippets.get(i).text() : "No snippet available.";

                results.append(i + 1).append(". ").append(title).append("\n");
                results.append("   - ").append(snippet).append("\n");
                results.append("   - Link: ").append(href).append("\n\n");
            }

            return results.toString();
        }
    }
}
