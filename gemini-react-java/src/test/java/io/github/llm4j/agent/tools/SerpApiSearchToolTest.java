package io.github.llm4j.agent.tools;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SerpApiSearchToolTest {

    private MockWebServer mockWebServer;
    private SerpApiSearchTool serpApiSearchTool;
    private final String apiKey = "test-serp-api-key";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Use an Interceptor to redirect serpapi.com to localhost for testing
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    okhttp3.Request original = chain.request();
                    okhttp3.HttpUrl newUrl = original.url().newBuilder()
                            .scheme("http")
                            .host(mockWebServer.getHostName())
                            .port(mockWebServer.getPort())
                            .build();
                    return chain.proceed(original.newBuilder().url(newUrl).build());
                })
                .build();

        serpApiSearchTool = new SerpApiSearchTool(apiKey, client);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void testMissingApiKey() {
        SerpApiSearchTool tool = new SerpApiSearchTool(null);
        String result = tool.execute(Map.of("query", "test"));
        assertThat(result).contains("Error: SerpAPI key not configured");
    }

    @Test
    void testMissingQuery() {
        String result = serpApiSearchTool.execute(Map.of());
        assertThat(result).contains("Error: No search 'query' provided");
    }

    @Test
    void testSuccessfulSearch() {
        String jsonResponse = """
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
    void testNoResults() {
        String jsonResponse = """
                {
                  "organic_results": []
                }
                """;
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        String result = serpApiSearchTool.execute(Map.of("query", "test serp query"));

        assertThat(result).contains("No search results found");
    }

    @Test
    void testApiError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401).setBody("Invalid API Key"));

        String result = serpApiSearchTool.execute(Map.of("query", "test serp query"));

        assertThat(result).contains("SerpAPI error (HTTP 401)");
    }
}
