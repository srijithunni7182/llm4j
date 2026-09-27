package io.github.llm4j.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DuckDuckGoSearchToolTest {

    private MockWebServer mockWebServer;
    private DuckDuckGoSearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        tool = new DuckDuckGoSearchTool(new okhttp3.OkHttpClient(), baseUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void testGetName() {
        assertThat(tool.getName()).isEqualTo("WebSearch");
    }

    @Test
    void testDescription() {
        assertThat(tool.getDescription()).contains("current information");
    }

    @Test
    void testExecuteWithNoQuery() throws Exception {
        String result = tool.execute(Map.of());
        assertThat(result).contains("Error: No search 'query' provided.");
    }

    @Test
    void testPerformSearchSuccess() throws Exception {
        String htmlResponse =
                "<html><body>"
                        + "<table><tr>"
                        + "<td><a class=\"result-link\" href=\"https://en.wikipedia.org/wiki/Tokyo\">Tokyo - Wikipedia</a></td>"
                        + "<td class=\"result-snippet\">Tokyo is the capital of Japan.</td>"
                        + "</tr></table>"
                        + "</body></html>";

        mockWebServer.enqueue(
                new MockResponse().setBody(htmlResponse).addHeader("Content-Type", "text/html"));

        String result = tool.execute(Map.of("query", "Tokyo"));

        assertThat(result).contains("Search Results (DuckDuckGo Lite) for 'Tokyo':");
        assertThat(result).contains("1. Tokyo - Wikipedia");
        assertThat(result).contains("Tokyo is the capital of Japan.");
        assertThat(result).contains("Link: https://en.wikipedia.org/wiki/Tokyo");
    }

    @Test
    void testPerformSearchWithRedirect() throws Exception {
        String htmlResponse =
                "<html><body>"
                        + "<table><tr>"
                        + "<td><a class=\"result-link\" href=\"/l/?uddg=https%3A%2F%2Fjava.com%2F&rut=...\">Java | Oracle</a></td>"
                        + "<td class=\"result-snippet\">Java Download.</td>"
                        + "</tr></table>"
                        + "</body></html>";

        mockWebServer.enqueue(
                new MockResponse().setBody(htmlResponse).addHeader("Content-Type", "text/html"));

        String result = tool.execute(Map.of("query", "Java"));

        assertThat(result).contains("1. Java | Oracle");
        assertThat(result).contains("Link: https://java.com/");
    }

    @Test
    void testNoResults() throws Exception {
        String htmlResponse = "<html><body>No results found</body></html>";

        // Nothing from the GET, nothing from the POST fallback either.
        mockWebServer.enqueue(
                new MockResponse().setBody(htmlResponse).addHeader("Content-Type", "text/html"));
        mockWebServer.enqueue(
                new MockResponse().setBody(htmlResponse).addHeader("Content-Type", "text/html"));

        String result = tool.execute(Map.of("query", "UnknownThing"));

        assertThat(result)
                .contains("Error: No search results found for 'UnknownThing' on DuckDuckGo Lite.");
    }

    @Test
    void testApiError() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        String result = tool.execute(Map.of("query", "error"));

        assertThat(result).contains("Error: DuckDuckGo Lite returned HTTP 500");
    }

    @Test
    void fallsBackToTheLiteFormPostWhenTheGetReturnsNothing() throws Exception {
        mockWebServer.enqueue(new MockResponse().setBody("<html><body></body></html>"));
        mockWebServer.enqueue(new MockResponse().setBody("<table><tr><td>"
                + "<a class=\"result-link\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fbread&rut=x\">Bread science</a>"
                + "</td></tr><tr><td class=\"result-snippet\">Cold proofing slows yeast.</td></tr></table>"));

        java.util.List<DuckDuckGoSearchTool.Result> results = tool.search("cold proof", 5);

        assertThat(results).containsExactly(new DuckDuckGoSearchTool.Result(
                "Bread science", "https://example.org/bread", "Cold proofing slows yeast."));
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("GET");
        okhttp3.mockwebserver.RecordedRequest post = mockWebServer.takeRequest();
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getBody().readUtf8()).isEqualTo("q=cold+proof");
    }

    @Test
    void skipsSponsoredResults() {
        java.util.List<DuckDuckGoSearchTool.Result> results = DuckDuckGoSearchTool.parse("<table>"
                + "<tr><td><a class=\"result-link\" href=\"https://duckduckgo.com/y.js?ad=1\">Ad</a></td></tr>"
                + "<tr><td><a class=\"result-link\" href=\"https://example.org/\">Real</a></td></tr></table>", 5);
        assertThat(results).extracting(DuckDuckGoSearchTool.Result::title).containsExactly("Real");
    }
}
