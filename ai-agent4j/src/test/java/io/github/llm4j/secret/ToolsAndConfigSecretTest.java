package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.memory.SemanticMemoryConfig;
import io.github.llm4j.agent.skill.RestSkillRegistry;
import io.github.llm4j.agent.tools.SerpApiSearchTool;
import io.github.llm4j.agent.tools.WebSearchTool;
import io.github.llm4j.agent.tools.openapi.OpenAPITool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.agent.tools.openapi.OpenAPIEndpoint;
import io.github.llm4j.agent.tools.openapi.OpenAPISpec;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolsAndConfigSecretTest {

    private MockWebServer server;
    private InMemorySecretStore store;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new InMemorySecretStore();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private String base() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    /** A client that sends every request to the mock server, whatever host the tool asked for. */
    private OkHttpClient redirecting() {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            okhttp3.Request r = chain.request();
            okhttp3.HttpUrl to = r.url().newBuilder().scheme("http").host(server.getHostName()).port(server.getPort()).build();
            return chain.proceed(r.newBuilder().url(to).build());
        }).build();
    }

    // ---- LLMConfig ----------------------------------------------------------------------------------------------

    @Test
    void configNeverHoldsOrPrintsTheKey() {
        store.put("k", FakeKeys.ONE);
        LLMConfig config = LLMConfig.builder().apiKey(SecretRef.of(store, "k")).build();
        assertEquals("secret:k", config.getApiKeyRef().toString());
        assertTrue(config.toString().contains("secret:k"));
        assertFalse(config.toString().contains(FakeKeys.ONE));
        assertFalse(LLMConfig.builder().apiKey(FakeKeys.ONE).build().toString().contains(FakeKeys.ONE), "even a literal key is not printed");
        assertTrue(config.hasApiKey());
        assertEquals(FakeKeys.ONE, config.getApiKey());
        store.put("k", FakeKeys.TWO);
        assertEquals(FakeKeys.TWO, config.getApiKey(), "fetched on demand");
    }

    @Test
    void configEqualityNeverComparesTheValueOfAStoredSecret() {
        store.put("k", FakeKeys.ONE);
        LLMConfig a = LLMConfig.builder().apiKey(SecretRef.of(store, "k")).build();
        LLMConfig b = LLMConfig.builder().apiKey(SecretRef.of(store, "k")).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(LLMConfig.builder().apiKey(FakeKeys.ONE).build(), LLMConfig.builder().apiKey(FakeKeys.ONE).build());
        assertEquals(LLMConfig.builder().apiKey(FakeKeys.ONE).build().hashCode(), LLMConfig.builder().apiKey(FakeKeys.TWO).build().hashCode(),
                "a hash must not depend on a literal key");
        assertNotEquals(LLMConfig.builder().apiKey(FakeKeys.ONE).build(), LLMConfig.builder().apiKey(FakeKeys.TWO).build());
    }

    @Test
    void configWithoutAKeyOrWithABlankOne() {
        assertNull(LLMConfig.builder().build().getApiKey());
        assertNull(LLMConfig.builder().build().getApiKeyRef());
        assertFalse(LLMConfig.builder().build().hasApiKey());
        assertFalse(LLMConfig.builder().apiKey("").build().hasApiKey());
        assertFalse(LLMConfig.builder().apiKey("   ").build().hasApiKey());
        assertFalse(LLMConfig.builder().apiKey((String) null).build().hasApiKey());
        assertFalse(LLMConfig.builder().apiKey(SecretRef.of(store, "nope")).build().hasApiKey());
    }

    @Test
    void requireApiKeyChecksTheHostOfTheBaseUrl() {
        store.put("k", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
        LLMConfig config = LLMConfig.builder().apiKey(SecretRef.of(store, "k")).build();
        assertEquals(FakeKeys.ONE, config.requireApiKey("X", "https://api.example.com/v1"));
        io.github.llm4j.exception.AuthenticationException e = assertThrows(io.github.llm4j.exception.AuthenticationException.class,
                () -> config.requireApiKey("X", "https://evil.example.net/v1"));
        assertFalse(e.getMessage().contains(FakeKeys.ONE));
        assertTrue(e.getMessage().contains("evil.example.net"));
    }

    // ---- search tools -------------------------------------------------------------------------------------------

    @Test
    void serpApiFetchesItsKeyPerSearchAndScrubsErrors() throws Exception {
        store.put("serp", FakeKeys.ONE);
        SerpApiSearchTool tool = SerpApiSearchTool.withSecret(SecretRef.of(store, "serp"), new OkHttpClient(), base() + "/search");
        server.enqueue(new MockResponse().setBody("{\"organic_results\":[]}"));
        tool.execute(Map.of("query", "java"));
        assertTrue(server.takeRequest().getPath().contains("api_key=" + FakeKeys.ONE));
        store.put("serp", FakeKeys.TWO);
        server.enqueue(new MockResponse().setBody("{\"organic_results\":[]}"));
        tool.execute(Map.of("query", "java"));
        assertTrue(server.takeRequest().getPath().contains("api_key=" + FakeKeys.TWO), "the rotated key is used");

        server.enqueue(new MockResponse().setResponseCode(401).setBody("bad key " + FakeKeys.TWO));
        String result = tool.execute(Map.of("query", "java"));
        assertFalse(result.contains(FakeKeys.TWO), result);
        assertTrue(result.contains("***"), result);
    }

    @Test
    void serpApiRefusesAHostTheSecretIsNotBoundTo() throws Exception {
        store.put("serp", FakeKeys.ONE, SecretMetadata.allowing("serpapi.com"));
        SerpApiSearchTool tool = SerpApiSearchTool.withSecret(SecretRef.of(store, "serp"), new OkHttpClient(), base() + "/search");
        String result = tool.execute(Map.of("query", "java"));
        assertTrue(result.startsWith("Error"), result);
        assertFalse(result.contains(FakeKeys.ONE));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void searchToolsReportAMissingSecretWithoutSendingAnything() throws Exception {
        String serp = SerpApiSearchTool.withSecret(SecretRef.of(store, "gone"), new OkHttpClient(), base()).execute(Map.of("query", "x"));
        assertTrue(serp.contains("secret:gone is not in the store"), serp);
        String web = WebSearchTool.withSecret(SecretRef.of(store, "gone"), "cx").execute(Map.of("query", "x"));
        assertTrue(web.contains("secret:gone is not in the store"), web);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void stringConstructorsOfTheSearchToolsStillWork() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"organic_results\":[]}"));
        new SerpApiSearchTool(FakeKeys.ONE, new OkHttpClient(), base() + "/search").execute(Map.of("query", "x"));
        assertTrue(server.takeRequest().getPath().contains("api_key=" + FakeKeys.ONE));
        assertTrue(new SerpApiSearchTool(null).execute(Map.of("query", "x")).contains("not configured"));
        assertTrue(new WebSearchTool(null, "cx").execute(Map.of("query", "x")).contains("not configured"));
    }

    @Test
    void webSearchBindsTheKeyToGoogleApisAndScrubsErrors() throws Exception {
        store.put("g", FakeKeys.ONE, SecretMetadata.allowing("www.googleapis.com"));
        WebSearchTool tool = WebSearchTool.withSecret(SecretRef.of(store, "g"), "cx-id", redirecting());
        server.enqueue(new MockResponse().setBody("{\"items\":[]}"));
        tool.execute(Map.of("query", "java"));
        RecordedRequest r = server.takeRequest();
        assertTrue(r.getPath().contains("key=" + FakeKeys.ONE), r.getPath());

        server.enqueue(new MockResponse().setResponseCode(403).setBody("key " + FakeKeys.ONE + " blocked"));
        String result = tool.execute(Map.of("query", "java"));
        assertFalse(result.contains(FakeKeys.ONE), result);

        store.put("g", FakeKeys.ONE, SecretMetadata.allowing("serpapi.com"));
        String refused = tool.execute(Map.of("query", "java"));
        assertTrue(refused.startsWith("Error"), refused);
        assertEquals(2, server.getRequestCount(), "the refused search sent nothing");
    }

    // ---- OpenAPI tool and REST skills ---------------------------------------------------------------------------

    private OpenAPITool openApi(OpenAPITool.Builder b) {
        OpenAPISpec spec = OpenAPISpec.builder().title("t").version("1").servers(List.of(base()))
                .endpoints(List.of(OpenAPIEndpoint.builder().path("/ping").method("GET").summary("ping").parameters(List.of()).build())).build();
        return b.name("api").spec(spec).build();
    }

    private static final Map<String, Object> PING = Map.of("endpoint", "/ping", "method", "GET");

    @Test
    void openApiToolFetchesQueryAndHeaderCredentialsPerRequest() throws Exception {
        store.put("q", FakeKeys.ONE);
        store.put("h", "Bearer " + FakeKeys.TWO);
        OpenAPITool tool = openApi(OpenAPITool.builder().apiKeyAuth("api_key", SecretRef.of(store, "q")).headerAuth("Authorization", SecretRef.of(store, "h")));
        server.enqueue(new MockResponse().setBody("{}"));
        tool.execute(PING);
        RecordedRequest r = server.takeRequest();
        assertTrue(r.getPath().contains("api_key=" + FakeKeys.ONE), r.getPath());
        assertEquals("Bearer " + FakeKeys.TWO, r.getHeader("Authorization"));
        store.put("q", "rotated-0003");
        server.enqueue(new MockResponse().setBody("{}"));
        tool.execute(PING);
        assertTrue(server.takeRequest().getPath().contains("api_key=rotated-0003"));
    }

    @Test
    void openApiToolDoesNotReturnOrLogACredentialTheServerEchoes() throws Exception {
        store.put("q", FakeKeys.ONE);
        OpenAPITool tool = openApi(OpenAPITool.builder().apiKeyAuth("api_key", SecretRef.of(store, "q")).headerAuth("X-Literal", "literal-credential-0009"));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("rejected " + FakeKeys.ONE + " and literal-credential-0009"));
        String result = tool.execute(PING);
        assertFalse(result.contains(FakeKeys.ONE), result);
        assertFalse(result.contains("literal-credential-0009"), result);
        assertTrue(result.contains("***"), result);
    }

    @Test
    void openApiToolRefusesAHostTheSecretIsNotBoundTo() throws Exception {
        store.put("q", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
        OpenAPITool tool = openApi(OpenAPITool.builder().apiKeyAuth("api_key", SecretRef.of(store, "q")));
        String result = tool.execute(PING);
        assertTrue(result.contains("credential unavailable"), result);
        assertFalse(result.contains(FakeKeys.ONE));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void restSkillRegistryFetchesItsKeyPerRequestAndScrubsErrors() throws Exception {
        store.put("r", FakeKeys.ONE);
        RestSkillRegistry registry = RestSkillRegistry.builder().baseUrl(base()).apiKey(SecretRef.of(store, "r")).build();
        server.enqueue(new MockResponse().setBody("[]"));
        registry.searchSkills("x");
        RecordedRequest first = server.takeRequest();
        assertEquals("Bearer " + FakeKeys.ONE, first.getHeader("Authorization"));
        assertEquals(FakeKeys.ONE, first.getHeader("x-api-key"));
        store.put("r", FakeKeys.TWO);
        server.enqueue(new MockResponse().setBody("[]"));
        registry.searchSkills("x");
        assertEquals(FakeKeys.TWO, server.takeRequest().getHeader("x-api-key"));

        server.enqueue(new MockResponse().setResponseCode(401).setBody("unknown key " + FakeKeys.TWO));
        IOException e = assertThrows(IOException.class, () -> registry.searchSkills("x"));
        assertFalse(e.getMessage().contains(FakeKeys.TWO), e.getMessage());

        store.put("r", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
        IOException refused = assertThrows(IOException.class, () -> registry.getSkill("abc"));
        assertTrue(refused.getMessage().contains("credential unavailable"));
        assertEquals(3, server.getRequestCount(), "nothing was sent when the host was refused");
    }

    // ---- semantic memory config ---------------------------------------------------------------------------------

    @Test
    void semanticMemoryConfigAcceptsReferences() {
        store.put("g", FakeKeys.ONE);
        store.put("pg", FakeKeys.TWO);
        SemanticMemoryConfig config = SemanticMemoryConfig.builder().userId("u")
                .geminiApiKey(SecretRef.of(store, "g"))
                .pgPassword(SecretRef.of(store, "pg"))
                .build();
        assertEquals("secret:g", config.getGeminiApiKeyRef().toString());
        assertEquals("secret:pg", config.getPgPasswordRef().toString());
        assertEquals(FakeKeys.ONE, config.getGeminiApiKey());
        SemanticMemoryConfig literal = SemanticMemoryConfig.builder().userId("u").geminiApiKey(FakeKeys.ONE).pgPassword("pw").build();
        assertEquals(FakeKeys.ONE, literal.getGeminiApiKey());
        assertEquals("pw", literal.getPgPassword());
        assertNull(SemanticMemoryConfig.builder().userId("u").build().getPgPasswordRef());
    }
}
