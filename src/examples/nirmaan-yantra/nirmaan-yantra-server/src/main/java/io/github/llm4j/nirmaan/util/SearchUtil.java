package io.github.llm4j.nirmaan.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SearchUtil {

    private static final String DDG_LITE_URL = "https://lite.duckduckgo.com/lite/";

    public static List<String> search(String query) {
        List<String> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            Document doc = Jsoup.connect(DDG_LITE_URL)
                    .data("q", query)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(5000)
                    .post();

            // DDG Lite structure: table -> tr -> td -> a.result-link
            Elements links = doc.select("a.result-link");
            Elements snippets = doc.select("td.result-snippet");

            for (int i = 0; i < Math.min(links.size(), 5); i++) {
                Element link = links.get(i);
                String title = link.text();
                String url = link.attr("href");
                String snippet = (i < snippets.size()) ? snippets.get(i).text() : "";

                results.add(String.format("Title: %s\nURL: %s\nSnippet: %s", title, url, snippet));
            }

            if (results.isEmpty()) {
                results.add("No results found for: " + query);
            }

        } catch (Exception e) {
            results.add("Search Error: " + e.getMessage());
        }
        return results;
    }
}
