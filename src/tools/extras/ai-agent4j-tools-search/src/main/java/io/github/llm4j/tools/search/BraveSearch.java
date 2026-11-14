package io.github.llm4j.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * The reference provider: the Brave Search API ({@code provider: brave}). It needs a key of your
 * own, from Brave's site; its terms and prices are Brave's. It is an example of how a provider is
 * written as much as a provider: another service is a class of about this size.
 */
public final class BraveSearch implements SearchProvider {

    static final String HOST = "api.search.brave.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpUrl endpoint;

    /** The provider as {@link java.util.ServiceLoader} makes it: Brave's own address. */
    public BraveSearch() {
        this(HttpUrl.get("https://" + HOST + "/res/v1/web/search"));
    }

    /** A provider that calls {@code endpoint}: a seam for tests. */
    BraveSearch(HttpUrl endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public String host() {
        return HOST;
    }

    @Override
    public List<SearchHit> search(String query, int max, String apiKey, HttpSupport http)
            throws IOException {
        HttpUrl url =
                endpoint.newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("count", String.valueOf(max))
                        .build();
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", apiKey)
                        .get()
                        .build();
        HttpSupport.Reply reply = http.send(request, HttpSupport.Retry.ALL);
        if (!reply.success())
            throw new ToolRefusal("the search provider answered HTTP " + reply.status());
        JsonNode results = JSON.readTree(reply.bodyText()).path("web").path("results");
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode r : results) {
            if (hits.size() >= max) break;
            String link = r.path("url").asText("");
            if (link.isEmpty()) continue;
            hits.add(new SearchHit(r.path("title").asText(""), link, r.path("description").asText("")));
        }
        return hits;
    }
}
