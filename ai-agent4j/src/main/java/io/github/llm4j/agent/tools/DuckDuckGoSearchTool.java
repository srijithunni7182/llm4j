package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.FormBody;
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

    /** One DuckDuckGo Lite result. */
    public record Result(String title, String url, String snippet) { }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /**
     * Searches DuckDuckGo Lite and returns up to {@code max} results with their real destination URLs.
     * Tries a GET first and, if DuckDuckGo answers with no results (it sometimes does for GETs),
     * submits the Lite search form as a POST.
     */
    public List<Result> search(String query, int max) throws IOException {
        List<Result> results = parse(fetch(new Request.Builder()
                .url(baseUrl + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8))), max);
        if (results.isEmpty()) {
            results = parse(fetch(new Request.Builder().url(baseUrl)
                    .post(new FormBody.Builder().add("q", query).build())), max);
        }
        return results;
    }

    private String fetch(Request.Builder request) throws IOException {
        try (Response response = httpClient.newCall(request
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DuckDuckGo Lite returned HTTP " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    /** DDG Lite lists titles as {@code a.result-link} and snippets as {@code td.result-snippet}. */
    static List<Result> parse(String html, int max) {
        Document doc = Jsoup.parse(html);
        Elements links = doc.select("a.result-link");
        Elements snippets = doc.select("td.result-snippet");
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < links.size() && results.size() < max; i++) {
            Element link = links.get(i);
            String href = cleanHref(link.attr("href"));
            if (href.isBlank() || href.contains("duckduckgo.com/y.js")) continue; // sponsored
            String snippet = i < snippets.size() ? snippets.get(i).text() : "";
            results.add(new Result(link.text(), href, snippet));
        }
        return results;
    }

    /** Lite links are sometimes DuckDuckGo redirects ({@code /l/?uddg=<real url>}); returns the real URL. */
    static String cleanHref(String href) {
        if (href == null) return "";
        if (href.contains("uddg=")) {
            try {
                String encoded = href.split("uddg=", 2)[1].split("&")[0];
                href = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // keep as is
            }
        }
        if (href.startsWith("//")) href = "https:" + href;
        return href;
    }

    private String performSearch(String query) {
        List<Result> results;
        try {
            results = search(query, 5);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        if (results.isEmpty()) {
            return "Error: No search results found for '" + query + "' on DuckDuckGo Lite.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Search Results (DuckDuckGo Lite) for '").append(query).append("':\n\n");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            out.append(i + 1).append(". ").append(r.title()).append("\n");
            out.append("   - ").append(r.snippet().isBlank() ? "No snippet available." : r.snippet()).append("\n");
            out.append("   - Link: ").append(r.url()).append("\n\n");
        }
        return out.toString();
    }
}
