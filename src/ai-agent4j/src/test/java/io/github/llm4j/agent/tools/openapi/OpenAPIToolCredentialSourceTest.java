package io.github.llm4j.agent.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.tools.openapi.FakeApi.Reply;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.SecretException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** An {@link OpenAPITool} whose credential is asked for on every request, against a real server on localhost. */
class OpenAPIToolCredentialSourceTest {

    private FakeApi api;

    @BeforeEach
    void startApi() {
        api = new FakeApi();
    }

    @AfterEach
    void stopApi() {
        api.close();
    }

    /** A source that hands out the values in order (the last one again and again) and counts what happens to it. */
    private static final class Tokens implements CredentialSource {
        final List<String> values;
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger rejections = new AtomicInteger();

        Tokens(String... values) {
            this.values = List.of(values);
        }

        @Override
        public String get() {
            return values.get(Math.min(gets.getAndIncrement(), values.size() - 1));
        }

        @Override
        public void rejected() {
            rejections.incrementAndGet();
        }
    }

    private OpenAPITool tool(String server, java.util.function.Consumer<OpenAPITool.Builder> auth) {
        OpenAPISpec spec = OpenAPISpec.builder().title("Refunds").version("1").servers(List.of(server))
                .endpoints(List.of(
                        OpenAPIEndpoint.builder().path("/refunds").method("GET").summary("List").build(),
                        OpenAPIEndpoint.builder().path("/refunds").method("POST").summary("Create").build()))
                .build();
        OpenAPITool.Builder builder = OpenAPITool.builder().name("Refunds").spec(spec);
        auth.accept(builder);
        return builder.build();
    }

    private static Map<String, Object> get() {
        return Map.of("endpoint", "/refunds", "method", "GET");
    }

    private static Map<String, Object> post(Map<String, Object> body) {
        return Map.of("endpoint", "/refunds", "method", "POST", "body", body);
    }

    @Test
    @Tag("CC-V2.1")
    void theHeaderCredentialIsOnTheRequest() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", CredentialSource.of("Bearer t1")));

        assertThat(tool.execute(get())).isEqualTo("{}");

        assertThat(api.requests()).hasSize(1);
        assertThat(api.requests().get(0).header("Authorization")).isEqualTo("Bearer t1");
    }

    @Test
    @Tag("CC-V2.2")
    void theQueryCredentialIsOnTheRequest() {
        OpenAPITool tool = tool(api.url(), b -> b.apiKeyAuth("access_token", CredentialSource.of("t1")));

        tool.execute(get());

        assertThat(api.requests().get(0).target()).isEqualTo("/refunds?access_token=t1");
    }

    @Test
    @Tag("CC-V2.3")
    @Tag("CC-V2.4")
    void theSourceIsAskedOnEveryRequestSoARefreshedTokenIsUsedAtOnce() {
        Tokens tokens = new Tokens("Bearer t1", "Bearer t2", "Bearer t3");
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", tokens));

        tool.execute(get());
        tool.execute(get());
        tool.execute(get());

        assertThat(tokens.gets).hasValue(3);
        assertThat(tokens.rejections).hasValue(0);
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer t1", "Bearer t2", "Bearer t3");
    }

    @Test
    @Tag("CC-V2.5")
    void aRefusedTokenIsRejectedFetchedAgainAndTheRequestRepeatedOnce() {
        api.replies(Reply.status(401, "expired"), Reply.ok("{\"refunds\":[]}"));
        Tokens tokens = new Tokens("Bearer old", "Bearer new");
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", tokens));

        String result = tool.execute(get());

        assertThat(result).isEqualTo("{\"refunds\":[]}");
        assertThat(tokens.rejections).hasValue(1);
        assertThat(tokens.gets).hasValue(2);
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer old", "Bearer new");
    }

    @Test
    @Tag("CC-V2.6")
    void aSecondRefusalIsTheAnswerAndIsNotRepeated() {
        api.replies(Reply.status(401, "no"), Reply.status(401, "still no"));
        Tokens tokens = new Tokens("Bearer old", "Bearer new");
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", tokens));

        String result = tool.execute(get());

        assertThat(result).isEqualTo("Error: API returned status code 401. Body: still no");
        assertThat(api.requests()).hasSize(2);
        assertThat(tokens.rejections).hasValue(1);
    }

    @Test
    @Tag("CC-V2.6")
    void otherFailuresAreNotRepeated() {
        api.replies(Reply.status(403, "forbidden"));
        Tokens tokens = new Tokens("Bearer t");
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", tokens));

        assertThat(tool.execute(get())).isEqualTo("Error: API returned status code 403. Body: forbidden");

        assertThat(api.requests()).hasSize(1);
        assertThat(tokens.rejections).hasValue(0);
    }

    @Test
    @Tag("CC-V2.6")
    void aRefusalOfAFixedKeyIsNotRepeatedBecauseAskingAgainCannotHelp() {
        api.replies(Reply.status(401, "bad key"));
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("X-Api-Key", "fixed-key-value"));

        assertThat(tool.execute(get())).startsWith("Error: API returned status code 401");

        assertThat(api.requests()).hasSize(1);
    }

    @Test
    @Tag("CC-V2.7")
    void aRepeatedPostCarriesTheSameBody() {
        api.replies(Reply.status(401, "expired"), Reply.status(201, "{\"id\":7}"));
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", new Tokens("Bearer old", "Bearer new")));

        String result = tool.execute(post(Map.of("amount", 5)));

        assertThat(result).isEqualTo("{\"id\":7}");
        assertThat(api.requests()).extracting(FakeApi.Request::body).containsExactly("{\"amount\":5}", "{\"amount\":5}");
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer old", "Bearer new");
    }

    @Test
    @Tag("CC-V2.8")
    void withNothingToSendNothingIsSent() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", (CredentialSource) () -> null));

        assertThat(tool.execute(get())).isEqualTo("Error: credential unavailable: there is no credential for Authorization");

        assertThat(api.requests()).isEmpty();
    }

    @Test
    @Tag("CC-V2.9")
    void aCredentialThatCannotBeObtainedIsAnObservationNotAnException() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", (CredentialSource) () -> {
            throw new SecretException("refresh token revoked");
        }));

        assertThat(tool.execute(get())).isEqualTo("Error: credential unavailable: refresh token revoked");

        assertThat(api.requests()).isEmpty();
    }

    @Test
    @Tag("CC-V4.2")
    void aSourceIsNotUsedOverPlainHttpToAnotherHost() {
        OpenAPITool tool = tool("http://api.invalid", b -> b.headerAuth("Authorization", CredentialSource.of("Bearer t")));

        String result = tool.execute(get());

        assertThat(result).startsWith("Error: credential unavailable: a credential for the person running this workflow is only sent over https");
    }

    @Test
    @Tag("CC-V4.3")
    void theAgentCannotChooseTheHost() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", CredentialSource.of("Bearer t")));

        for (String endpoint : List.of("http://evil.test/refunds", "@evil.test/refunds", "//evil.test/refunds", "/refunds/../../x")) {
            String result = tool.execute(Map.of("endpoint", endpoint, "method", "GET"));
            assertThat(result).startsWith("Error: Endpoint 'GET " + endpoint + "' not found");
        }

        assertThat(api.requests()).isEmpty();
    }

    @Test
    @Tag("CC-V4.4")
    void aRedirectToAnotherHostIsNotFollowedSoTheCredentialStaysHome() {
        try (FakeApi other = new FakeApi()) {
            api.replies(Reply.redirect(other.url() + "/stolen"));
            OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", CredentialSource.of("Bearer t")));

            String result = tool.execute(get());

            assertThat(result).startsWith("Error: API returned status code 302");
            assertThat(other.requests()).isEmpty();
        }
    }

    @Test
    @Tag("CC-V5.4")
    void aTokenEchoedInAnAnswerOrAnErrorIsMasked() {
        api.respondWith(r -> r.target().contains("fail")
                ? Reply.status(500, "oops " + r.header("Authorization"))
                : Reply.ok("you are " + r.header("Authorization")));
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", CredentialSource.of("Bearer SECRETVALUE-9f3a")));

        String ok = tool.execute(get());
        String error = tool(api.url() + "/fail", b -> b.headerAuth("Authorization", CredentialSource.of("Bearer SECRETVALUE-9f3a")))
                .execute(get());

        assertThat(ok).doesNotContain("SECRETVALUE").contains("you are ***");
        assertThat(error).doesNotContain("SECRETVALUE").contains("oops ***");
    }

    @Test
    @Tag("CC-V2.11")
    void theToolDescribesItsApiButNeverItsCredential() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("Authorization", CredentialSource.of("Bearer SECRETVALUE-9f3a")));

        assertThat(tool.getDescription()).contains("GET /refunds").doesNotContain("SECRETVALUE").doesNotContain("Bearer");
        assertThat(tool.toString()).doesNotContain("SECRETVALUE");
    }

    @Test
    @Tag("CC-V2.10")
    void aFixedHeaderAndAFixedQueryKeyStillWork() {
        OpenAPITool tool = tool(api.url(), b -> b.headerAuth("X-Api-Key", "fixed-key-value").apiKeyAuth("k", "fixed-q"));

        tool.execute(get());

        assertThat(api.requests().get(0).header("X-Api-Key")).isEqualTo("fixed-key-value");
        assertThat(api.requests().get(0).target()).isEqualTo("/refunds?k=fixed-q");
    }

    @Test
    @Tag("CC-V2.10")
    void anUnsupportedMethodIsReportedBeforeAnyCredentialIsFetched() {
        Tokens tokens = new Tokens("Bearer t");
        OpenAPISpec spec = OpenAPISpec.builder().title("T").version("1").servers(List.of(api.url()))
                .endpoints(List.of(OpenAPIEndpoint.builder().path("/x").method("PATCH").build())).build();
        OpenAPITool tool = OpenAPITool.builder().name("T").spec(spec).headerAuth("Authorization", tokens).build();

        assertThat(tool.execute(Map.of("endpoint", "/x", "method", "PATCH"))).isEqualTo("Error: Unsupported HTTP method: PATCH");

        assertThat(tokens.gets).hasValue(0);
        assertThat(new ArrayList<>(api.requests())).isEmpty();
    }

    @Test
    @Tag("CC-V2.10")
    void pathAndQueryParametersAreFilledInAndAnAbsentOneIsLeftOut() {
        OpenAPISpec spec = OpenAPISpec.builder().title("T").version("1").servers(List.of(api.url()))
                .endpoints(List.of(OpenAPIEndpoint.builder().path("/refunds/{id}").method("GET")
                        .parameters(List.of(
                                OpenAPIParameter.builder().name("id").in("path").required(true).build(),
                                OpenAPIParameter.builder().name("expand").in("query").build(),
                                OpenAPIParameter.builder().name("note").in("query").build(),
                                OpenAPIParameter.builder().name("trace").in("header").build()))
                        .build()))
                .build();
        OpenAPITool tool = OpenAPITool.builder().name("T").spec(spec).headerAuth("Authorization", CredentialSource.of("Bearer t")).build();

        tool.execute(Map.of("endpoint", "/refunds/{id}", "method", "GET",
                "parameters", Map.of("id", "42", "expand", "items", "trace", "abc")));

        assertThat(api.requests().get(0).target()).isEqualTo("/refunds/42?expand=items");
        assertThat(api.requests().get(0).header("trace")).isNull();
    }

    @Test
    @Tag("CC-V2.10")
    void aMissingRequiredParameterIsReportedBeforeAnyCredentialIsFetched() {
        Tokens tokens = new Tokens("Bearer t");
        OpenAPISpec spec = OpenAPISpec.builder().title("T").version("1").servers(List.of(api.url()))
                .endpoints(List.of(OpenAPIEndpoint.builder().path("/x").method("GET")
                        .parameters(List.of(OpenAPIParameter.builder().name("q").in("query").required(true).build())).build()))
                .build();
        OpenAPITool tool = OpenAPITool.builder().name("T").spec(spec).headerAuth("Authorization", tokens).build();

        assertThat(tool.execute(Map.of("endpoint", "/x", "method", "GET"))).isEqualTo("Error: Missing required parameter 'q'");

        assertThat(tokens.gets).hasValue(0);
    }

    @Test
    @Tag("CC-V2.10")
    void onlyTwoHundredsAreSuccessesAndLongAnswersAreCut() {
        api.replies(Reply.status(200, "b"), Reply.status(299, "c"), Reply.status(300, "d"),
                Reply.ok("x".repeat(2000)), Reply.ok("x".repeat(2001)));
        OpenAPITool tool = tool(api.url(), b -> { });

        assertThat(tool.execute(get())).isEqualTo("b");
        assertThat(tool.execute(get())).isEqualTo("c");
        assertThat(tool.execute(get())).isEqualTo("Error: API returned status code 300. Body: d");
        assertThat(tool.execute(get())).isEqualTo("x".repeat(2000));
        assertThat(tool.execute(get())).isEqualTo("x".repeat(2000) + "\n... (truncated due to length)");
    }

    @Test
    @Tag("CC-V5.4")
    void aCredentialIsMaskedFromSixCharactersUp() {
        api.respondWith(r -> Reply.ok("[" + r.header("X-Key") + "]"));

        assertThat(tool(api.url(), b -> b.headerAuth("X-Key", "abcdef")).execute(get())).isEqualTo("[***]");
        assertThat(tool(api.url(), b -> b.headerAuth("X-Key", "abcde")).execute(get())).isEqualTo("[abcde]");
    }

    @Test
    @Tag("CC-V2.11")
    void theBuilderReturnsItselfAndTheToolKnowsItsNameAndEndpoints() {
        OpenAPITool.Builder builder = OpenAPITool.builder();

        assertThat(builder.name("N")).isSameAs(builder);
        assertThat(builder.httpClient(java.net.http.HttpClient.newHttpClient())).isSameAs(builder);
        assertThat(builder.apiKeyAuth("k", "v")).isSameAs(builder);
        assertThat(builder.apiKeyAuth("k", CredentialSource.of("v"))).isSameAs(builder);
        assertThat(builder.apiKeyAuth("k", io.github.llm4j.secret.SecretRef.literal("v"))).isSameAs(builder);
        assertThat(builder.headerAuth("h", "v")).isSameAs(builder);
        assertThat(builder.headerAuth("h", CredentialSource.of("v"))).isSameAs(builder);
        assertThat(builder.headerAuth("h", io.github.llm4j.secret.SecretRef.literal("v"))).isSameAs(builder);
        OpenAPITool tool = tool(api.url(), b -> { });
        assertThat(tool.getName()).isEqualTo("Refunds");
        assertThat(tool.getDescription()).startsWith("Calls the Refunds API. Arguments:")
                .contains("\n- GET /refunds: List").contains("\n- POST /refunds: Create");
    }

    @Test
    @Tag("CC-V2.11")
    void theDescriptionShowsTheApisOwnDescriptionAndEachParameter() {
        OpenAPISpec spec = OpenAPISpec.builder().title("Flights").version("1").description("  Flight data  ")
                .servers(List.of(api.url()))
                .endpoints(List.of(
                        OpenAPIEndpoint.builder().path("/f").method("GET").description("Find flights")
                                .parameters(List.of(OpenAPIParameter.builder().name("from").in("query").type("string").build(),
                                        OpenAPIParameter.builder().name("n").in("query").build())).build(),
                        OpenAPIEndpoint.builder().path("/g").method("GET").build()))
                .build();

        String d = OpenAPITool.builder().name("F").spec(spec).build().getDescription();

        assertThat(d).startsWith("Calls the Flights API (Flight data). Arguments:")
                .contains("\n- GET /f: Find flights [params: from (query, string), n (query)]")
                .contains("\n- GET /g");
        assertThat(d).doesNotContain("\n- GET /g:");
    }

    @Test
    @Tag("CC-V2.11")
    void aToolWithoutATitleOrADescriptionSaysHttp() {
        OpenAPISpec spec = OpenAPISpec.builder().version("1").description(" ").servers(List.of(api.url())).endpoints(List.of()).build();

        assertThat(OpenAPITool.builder().name("X").spec(spec).build().getDescription()).startsWith("Calls the HTTP API. Arguments:");
    }

    @Test
    @Tag("CC-V2.11")
    void aToolNeedsANameAndASpec() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenAPITool.builder().spec(OpenAPISpec.builder().build()).build())
                .hasMessage("name is required");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenAPITool.builder().name("x").build()).hasMessage("spec is required");
    }

    @Test
    @Tag("CC-V2.10")
    void aMalformedCallIsReportedAsText() {
        OpenAPITool tool = tool(api.url(), b -> { });

        assertThat(tool.execute(Map.of("endpoint", 1, "method", "GET"))).isEqualTo("Error: 'endpoint' and 'method' arguments must be strings");
        assertThat(tool.execute(Map.of("endpoint", "/nope", "method", "GET"))).isEqualTo("Error: Endpoint 'GET /nope' not found in API specification");
    }

    @Test
    @Tag("CC-V2.9")
    void aServerThatIsNotThereIsReportedAsTextAndTheTokenIsNotInIt() {
        String gone;
        try (FakeApi closed = new FakeApi()) {
            gone = closed.url();
        }
        OpenAPITool tool = tool(gone, b -> b.headerAuth("Authorization", CredentialSource.of("Bearer SECRETVALUE-9f3a")));

        assertThat(tool.execute(get())).startsWith("Error executing API request: ").doesNotContain("SECRETVALUE");
    }
}
