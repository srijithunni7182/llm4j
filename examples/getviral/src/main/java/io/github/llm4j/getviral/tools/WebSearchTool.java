package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-web research in one call, numbered so agents can cite what they use.
 *
 * <ul>
 *   <li><b>Google Search</b> through Gemini's search grounding, when a Gemini key is available — a
 *       synthesised answer plus the web pages it was grounded on;</li>
 *   <li><b>GDELT</b> — the free global news index — for coverage from the last month;</li>
 *   <li><b>Wikipedia</b> full-text search for background;</li>
 *   <li><b>DuckDuckGo</b> instant answers for a quick definition.</li>
 * </ul>
 * The keyless sources always run, so research still works on Ollama or with no key at all.
 */
public class WebSearchTool extends PublicApiTool {

    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<String, Grounded> GROUNDED_CACHE = new ConcurrentHashMap<>();

    private final boolean offline;
    private final String geminiKey;
    private final String geminiModel;

    /**
     * @param geminiKey   enables Google Search grounding; {@code null} = keyless sources only
     * @param geminiModel a Gemini model that supports the {@code google_search} tool
     */
    public WebSearchTool(boolean offline, StudioEvents events, String geminiKey, String geminiModel) {
        super(offline, events);
        this.offline = offline;
        this.geminiKey = geminiKey == null || geminiKey.isBlank() ? null : geminiKey;
        this.geminiModel = geminiModel == null ? "gemini-2.5-flash" : geminiModel.replaceFirst("^(google|gemini)/", "");
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Searches the open web for a topic: " + (geminiKey != null ? "Google Search (via Gemini), " : "")
                + "news from the last month (GDELT), Wikipedia and DuckDuckGo. Returns numbered sources with URLs — "
                + "call read_page on the best ones before citing them. Args: {\"query\": \"what to research\"}.";
    }

    /** One search result, numbered in the order it is shown. */
    public record Source(String title, String url, String where, String snippet) { }

    record Grounded(String answer, List<Source> sources, List<String> queries) { }

    @Override
    public String execute(Map<String, Object> args) {
        String query = arg(args, "query", "q", "topic");
        if (query.isBlank()) return "web_search needs {\"query\": \"...\"}.";

        CompletableFuture<Grounded> google = CompletableFuture.supplyAsync(() -> google(query));
        CompletableFuture<List<Source>> news = CompletableFuture.supplyAsync(() -> news(query));
        CompletableFuture<List<Source>> wiki = CompletableFuture.supplyAsync(() -> wikipedia(query));
        CompletableFuture<Source> instant = CompletableFuture.supplyAsync(() -> instantAnswer(query));

        StringBuilder out = new StringBuilder("Web research for \"").append(query).append("\":\n");
        Set<String> seen = new LinkedHashSet<>();
        int[] n = {0};

        Grounded g = google.join();
        if (g != null) {
            out.append("\nGoogle Search (via Gemini)")
               .append(g.queries().isEmpty() ? "" : " — searched: " + String.join(" · ", g.queries())).append('\n')
               .append(clip(g.answer(), 1400)).append('\n');
            appendAll(out, g.sources(), seen, n, 8);
        } else if (geminiKey == null) {
            out.append("\n(Google Search needs a Gemini key — using the open sources below.)\n");
        }

        List<Source> recent = news.join();
        if (!recent.isEmpty()) {
            out.append("\nNews coverage, last 30 days (GDELT):\n");
            appendAll(out, recent, seen, n, 6);
        }
        List<Source> background = wiki.join();
        if (!background.isEmpty()) {
            out.append("\nBackground (Wikipedia):\n");
            appendAll(out, background, seen, n, 3);
        }
        Source quick = instant.join();
        if (quick != null && seen.add(quick.url())) {
            out.append("\nQuick answer (DuckDuckGo):\n");
            appendAll(out, List.of(quick), seen, n, 1);
        }
        if (n[0] == 0) {
            out.append("\nNo sources found. Say so plainly rather than guessing; keep claims experiential.\n");
        } else {
            out.append("\nNext: read_page the 2-3 most useful URLs, then cite them by URL.\n");
        }
        out.append(offline ? "[source: offline — live web unreachable, results are samples]" : "[source: live web]");
        return out.toString();
    }

    private static void appendAll(StringBuilder out, List<Source> sources, Set<String> seen, int[] n, int max) {
        int added = 0;
        for (Source s : sources) {
            if (added >= max) break;
            if (s.url() == null || s.url().isBlank() || !seen.add(s.url())) continue;
            n[0]++;
            added++;
            out.append('[').append(n[0]).append("] ").append(clip(s.title(), 120));
            if (!s.where().isBlank()) out.append(" — ").append(s.where());
            out.append("\n    ").append(s.url()).append('\n');
            if (!s.snippet().isBlank()) out.append("    ").append(clip(s.snippet(), 260)).append('\n');
        }
    }

    // ── Google Search via Gemini grounding ───────────────────────────────────────────────────

    private Grounded google(String query) {
        if (offline || geminiKey == null) return null;
        Grounded cached = GROUNDED_CACHE.get(query);
        if (cached != null) return cached;
        long start = System.nanoTime();
        String url = GEMINI + geminiModel + ":generateContent";
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text",
                            "Research this on the web for a content creator and report what is true, recent and debated. "
                                    + "Give 5-8 concrete findings (numbers, names, dates) as short bullet points, newest first. "
                                    + "Topic: " + query)))),
                    "tools", List.of(Map.of("google_search", Map.of())));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", geminiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                report("generativelanguage.googleapis.com", url, false, start, "HTTP " + response.statusCode());
                return null;
            }
            JsonNode candidate = JSON.readTree(response.body()).path("candidates").path(0);
            StringBuilder answer = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) answer.append(part.path("text").asText(""));
            JsonNode meta = candidate.path("groundingMetadata");
            List<Source> sources = new ArrayList<>();
            for (JsonNode chunk : meta.path("groundingChunks")) {
                JsonNode web = chunk.path("web");
                String title = web.path("title").asText("");
                // Grounding links are Google redirects to the page; the title is usually the site.
                sources.add(new Source(title.isBlank() ? "Web result" : title, web.path("uri").asText(""),
                        "Google Search", ""));
            }
            List<String> queries = new ArrayList<>();
            meta.path("webSearchQueries").forEach(q -> queries.add(q.asText()));
            report("generativelanguage.googleapis.com", url, true, start, "google_search, " + sources.size() + " sources");
            Grounded grounded = new Grounded(answer.toString().strip(), sources, queries);
            GROUNDED_CACHE.put(query, grounded);
            return grounded;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            report("generativelanguage.googleapis.com", url, false, start, e.getClass().getSimpleName());
            return null;
        }
    }

    // ── keyless sources ──────────────────────────────────────────────────────────────────────

    private List<Source> news(String query) {
        String q = query.replaceAll("[\"()]", " ").strip();
        if (q.split("\\s+").length > 1) q = "\"" + q + "\"";
        Fetched fetched = fetch("https://api.gdeltproject.org/api/v2/doc/doc?mode=artlist&format=json&maxrecords=12"
                + "&sort=hybridrel&timespan=1month&query=" + enc(q), "gdelt-artlist");
        List<Source> out = new ArrayList<>();
        Set<String> titles = new LinkedHashSet<>();
        for (JsonNode a : fetched.json().path("articles")) {
            String title = text(a, "title");
            if (title.isBlank() || !titles.add(title.toLowerCase())) continue;
            String date = text(a, "seendate");
            String when = date.length() >= 8 ? date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8) : "";
            out.add(new Source(title, text(a, "url"), join(text(a, "domain"), when), ""));
        }
        return out;
    }

    private List<Source> wikipedia(String query) {
        Fetched fetched = fetch("https://en.wikipedia.org/w/rest.php/v1/search/page?limit=3&q=" + enc(query),
                "wikipedia-search");
        List<Source> out = new ArrayList<>();
        for (JsonNode page : fetched.json().path("pages")) {
            String key = text(page, "key");
            if (key.isBlank()) continue;
            out.add(new Source(text(page, "title"), "https://en.wikipedia.org/wiki/" + key.replace(' ', '_'),
                    "Wikipedia", text(page, "excerpt").replaceAll("<[^>]+>", "")));
        }
        return out;
    }

    private Source instantAnswer(String query) {
        Fetched fetched = fetch("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" + enc(query),
                "duckduckgo-instant");
        JsonNode json = fetched.json();
        String abstractText = text(json, "AbstractText");
        String url = text(json, "AbstractURL");
        if (abstractText.isBlank() || url.isBlank()) return null;
        return new Source(text(json, "Heading"), url, text(json, "AbstractSource"), abstractText);
    }

    private static String join(String a, String b) {
        return a.isBlank() ? b : b.isBlank() ? a : a + ", " + b;
    }

    /** Test hook. */
    public static void clearGroundedCache() {
        GROUNDED_CACHE.clear();
    }
}
