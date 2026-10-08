package io.github.llm4j.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SerpApiSearchToolTest {

    private MockWebServer mockWebServer;
    private SerpApiSearchTool serpApiSearchTool;
    private final String apiKey = "test-serp-api-key";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/search").toString();
        serpApiSearchTool = new SerpApiSearchTool(apiKey, new okhttp3.OkHttpClient(), baseUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void testMissingApiKey() throws Exception {
        SerpApiSearchTool tool = new SerpApiSearchTool(null);
        String result = tool.execute(Map.of("query", "test"));
        assertThat(result).contains("Error: SerpAPI key not configured");
    }

    @Test
    void testMissingQuery() throws Exception {
        String result = serpApiSearchTool.execute(Map.of());
        assertThat(result).contains("Error: No search 'query' provided");
    }

    @Test
    void testSuccessfulSearch() throws Exception {
        String jsonResponse =
                """
                {
                  "organic_results": [
                    {
                      "title": "SerpAPI Result Title",
                      "snippet": "SerpAPI Result Snippet",
                      "link": "http://serpapi-example.com"
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        String result = serpApiSearchTool.execute(Map.of("query", "test serp query"));

        assertThat(result).contains("Search Results for 'test serp query'");
        assertThat(result).contains("SerpAPI Result Title");
        assertThat(result).contains("SerpAPI Result Snippet");
        assertThat(result).contains("http://serpapi-example.com");
    }

    @Test
    void testNoResults() throws Exception {
        String jsonResponse =
                """
                {
                  "organic_results": []
                }
                """;
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        String result = serpApiSearchTool.execute(Map.of("query", "test serp query"));

        assertThat(result).contains("No search results found");
    }

    @Test
    void testApiError() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401).setBody("Invalid API Key"));

        String result = serpApiSearchTool.execute(Map.of("query", "test serp query"));

        assertThat(result).contains("SerpAPI error (HTTP 401)");
    }
}
