package io.github.llm4j.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {

    private MockWebServer mockWebServer;
    private WebSearchTool webSearchTool;
    private final String apiKey = "test-api-key";
    private final String cx = "test-cx";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // We will intercept the performSearch method's URL construction mechanism
        // to point to localhost, OR we can rely on MockWebServer if we could change the
        // base URL.
        // However, looking at WebSearchTool.java, the URL `https://www.googleapis.com`
        // is hardcoded.
        // This is a problem for testing with MockWebServer unless we add an interceptor
        // or refactor the URL.
        //
        // Refactoring to allow base URL injection is better for testing.
        // For now, I will create the test assuming I will fix the hardcoded URL in the
        // next step.
        // I will initialize WebSearchTool later in the tests or use a subclass for
        // testing if needed.
        // BUT, since I already modified the constructor to accept OkHttpClient,
        // I can just use an Interceptor to rewrite the host in the OkHttpClient!
        // That is a cleaner way without changing the production code's URL constant if
        // strict.
        // But actually, changing the URL to be a configurable field is also good
        // design.

        // Let's use the Interceptor approach to redirect googleapis.com to localhost
        // for testing.
        OkHttpClient client =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    okhttp3.Request original = chain.request();
                                    okhttp3.HttpUrl newUrl =
                                            original.url()
                                                    .newBuilder()
                                                    .scheme("http")
                                                    .host(mockWebServer.getHostName())
                                                    .port(mockWebServer.getPort())
                                                    .build();
                                    return chain.proceed(original.newBuilder().url(newUrl).build());
                                })
                        .build();

        webSearchTool = new WebSearchTool(apiKey, cx, client);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void testMissingApiKey() {
        WebSearchTool tool = new WebSearchTool(null, cx);
        String result = tool.execute(Map.of("query", "test"));
        assertThat(result).contains("Error: Google API key not configured");
    }

    @Test
    void testMissingCx() {
        WebSearchTool tool = new WebSearchTool(apiKey, null);
        String result = tool.execute(Map.of("query", "test"));
        assertThat(result).contains("Error: Google Custom Search CX");
    }

    @Test
    void testMissingQuery() {
        String result = webSearchTool.execute(Map.of());
        assertThat(result).contains("Error: No search 'query' provided");
    }

    @Test
    void testSuccessfulSearch() {
        String jsonResponse =
                """
                {
                  "items": [
                    {
                      "title": "Test Title",
                      "snippet": "Test Snippet",
                      "link": "http://example.com"
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        String result = webSearchTool.execute(Map.of("query", "test query"));

        assertThat(result).contains("Search Results for 'test query'");
        assertThat(result).contains("Test Title");
        assertThat(result).contains("Test Snippet");
        assertThat(result).contains("http://example.com");
    }

    @Test
    void testNoResults() {
        String jsonResponse =
                """
                {
                  "items": []
                }
                """;
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        String result = webSearchTool.execute(Map.of("query", "test query"));

        assertThat(result).contains("No search results found");
    }

    @Test
    void testApiError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        String result = webSearchTool.execute(Map.of("query", "test query"));

        assertThat(result).contains("Search API error (HTTP 400)");
    }
}
