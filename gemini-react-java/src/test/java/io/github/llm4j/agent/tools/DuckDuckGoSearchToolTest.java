package io.github.llm4j.agent.tools;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(tool.getDescription()).contains("DuckDuckGo");
    }

    @Test
    void testExecuteWithNoQuery() throws Exception {
        String result = tool.execute(Map.of());
        assertThat(result).contains("Error: No search 'query' provided.");
    }

    @Test
    void testPerformSearchSuccess() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"AbstractText\": \"Tokyo is the capital of Japan.\", \"AbstractSource\": \"Wikipedia\", \"AbstractURL\": \"https://en.wikipedia.org/wiki/Tokyo\"}")
                .addHeader("Content-Type", "application/json"));

        String result = tool.execute(Map.of("query", "Tokyo"));

        assertThat(result).contains("Tokyo is the capital of Japan.");
        assertThat(result).contains("Source: Wikipedia");
        assertThat(result).contains("Link: https://en.wikipedia.org/wiki/Tokyo");
    }

    @Test
    void testPerformSearchRelatedTopics() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"AbstractText\": \"\", \"RelatedTopics\": [{\"Text\": \"Java is a programming language\", \"FirstURL\": \"https://java.com\"}]}")
                .addHeader("Content-Type", "application/json"));

        String result = tool.execute(Map.of("query", "Java"));

        assertThat(result).contains("Related Information:");
        assertThat(result).contains("Java is a programming language");
        assertThat(result).contains("Link: https://java.com");
    }

    @Test
    void testNoResults() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"AbstractText\": \"\", \"RelatedTopics\": []}")
                .addHeader("Content-Type", "application/json"));

        String result = tool.execute(Map.of("query", "UnknownThing"));

        assertThat(result).contains("No instant answer found");
    }

    @Test
    void testApiError() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        String result = tool.execute(Map.of("query", "error"));

        assertThat(result).contains("DuckDuckGo API error (HTTP 500)");
    }
}
