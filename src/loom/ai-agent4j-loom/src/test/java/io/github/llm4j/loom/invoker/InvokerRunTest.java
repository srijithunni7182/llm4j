package io.github.llm4j.loom.invoker;

import static io.github.llm4j.loom.invoker.InvokerHarness.call;
import static io.github.llm4j.loom.invoker.InvokerHarness.done;
import static io.github.llm4j.loom.invoker.InvokerHarness.listRefunds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.MissingInvokerCredentialException;
import io.github.llm4j.secret.SecretException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workflow whose tool acts as the person who runs it. */
class InvokerRunTest {

    @TempDir Path dir;

    InvokerHarness h;

    @BeforeEach
    void start() throws IOException {
        h = new InvokerHarness(dir);
    }

    @AfterEach
    void stop() throws IOException {
        h.close();
    }

    private HarnessExecutor run(String script, InvokerCredentials invoker) {
        HarnessExecutor e = h.executor(script);
        e.initialize();
        e.executeWorkflow("Main", Map.of(), invoker);
        return e;
    }

    @Test
    @Tag("CC-V2.1")
    void theToolCallsTheApiWithTheTokenOfWhoRunsTheWorkflow() {
        h.agent("Scout", listRefunds("ann"), done("two refunds"));

        HarnessExecutor e =
                run(InvokerHarness.SCRIPT, InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1");
        assertThat(h.requests.get(0).getPath()).isEqualTo("/refunds?who=ann");
        assertThat(e.getContext().getVariable("x")).isEqualTo("two refunds");
    }

    @Test
    @Tag("CC-V2.2")
    void theCredentialCanTravelInTheQueryInstead() {
        h.agent("Scout", listRefunds("ann"), done("ok"));

        run(
                InvokerHarness.SCRIPT.replace(
                        "auth_header: \"Authorization\"", "auth_query: \"access_token\""),
                InvokerCredentials.of(Map.of("token", "t1")));

        assertThat(h.requests.get(0).getRequestUrl().queryParameter("access_token"))
                .isEqualTo("t1");
        assertThat(h.requests.get(0).getHeader("Authorization")).isNull();
    }

    @Test
    @Tag("CC-V2.3")
    @Tag("CC-V2.4")
    void aTokenThatIsRefreshedDuringTheRunIsUsedFromThenOn() {
        h.agent("Scout", listRefunds("ann"), listRefunds("bob"), listRefunds("cat"), done("ok"));
        Tokens host = new Tokens("Bearer t1", "Bearer t2", "Bearer t3");

        run(InvokerHarness.SCRIPT, host);

        assertThat(h.authorizations()).containsExactly("Bearer t1", "Bearer t2", "Bearer t3");
        assertThat(host.gets).hasValue(3);
        assertThat(host.invalidated).isEmpty();
    }

    @Test
    @Tag("CC-V2.5")
    void aTokenTheServerRefusesIsInvalidatedFetchedAgainAndTheCallRepeated() {
        h.agent("Scout", listRefunds("ann"), done("ok"));
        h.respond(
                r ->
                        "Bearer old".equals(r.getHeader("Authorization"))
                                ? new MockResponse().setResponseCode(401).setBody("expired")
                                : new MockResponse().setBody("[1,2]"));
        Tokens host = new Tokens("Bearer old", "Bearer new");

        run(InvokerHarness.SCRIPT, host);

        assertThat(h.authorizations()).containsExactly("Bearer old", "Bearer new");
        assertThat(host.invalidated).containsExactly("token");
        assertThat(h.modelSaw.stream().anyMatch(m -> m.contains("[1,2]"))).isTrue();
    }

    @Test
    @Tag("CC-V2.8")
    void aCredentialTheHostPromisedButDoesNotHaveIsAnObservationAndNothingIsSent() {
        h.agent("Scout", listRefunds("ann"), done("gave up"));
        InvokerCredentials promisesButHasNothing =
                new InvokerCredentials() {
                    @Override
                    public String get(String name) {
                        return null;
                    }
                };

        HarnessExecutor e = run(InvokerHarness.SCRIPT, promisesButHasNothing);

        assertThat(h.requests).isEmpty();
        assertThat(
                        h.modelSaw.stream()
                                .anyMatch(
                                        m ->
                                                m.contains(
                                                        "Error: credential unavailable: the invoker has no credential token")))
                .isTrue();
        assertThat(e.getContext().getVariable("x")).isEqualTo("gave up");
    }

    @Test
    @Tag("CC-V2.9")
    void aCredentialTheHostCannotGetIsAnObservationAndTheRunGoesOn() {
        h.agent("Scout", listRefunds("ann"), done("without the api"));
        InvokerCredentials revoked =
                name -> {
                    throw new SecretException("refresh token revoked");
                };

        HarnessExecutor e = run(InvokerHarness.SCRIPT, revoked);

        assertThat(h.requests).isEmpty();
        assertThat(
                        h.modelSaw.stream()
                                .anyMatch(
                                        m ->
                                                m.contains(
                                                        "Error: credential unavailable: refresh token revoked")))
                .isTrue();
        assertThat(e.getContext().getVariable("x")).isEqualTo("without the api");
    }

    @Test
    @Tag("CC-V4.1")
    void aRunStartedWithoutTheCredentialsItNeedsStopsBeforeAnyStep() {
        HarnessExecutor e = h.executor(InvokerHarness.SCRIPT);
        e.initialize();

        for (InvokerCredentials who :
                new InvokerCredentials[] {
                    null, InvokerCredentials.none(), InvokerCredentials.of(Map.of("other", "x"))
                }) {
            assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of(), who))
                    .isInstanceOf(MissingInvokerCredentialException.class)
                    .hasMessageContaining("[token]");
        }
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()))
                .isInstanceOf(MissingInvokerCredentialException.class);

        assertThat(h.modelSaw).isEmpty();
        assertThat(h.requests).isEmpty();
    }

    @Test
    @Tag("CC-V4.1")
    void aFailedStartLeavesTheExecutorFreeToRun() {
        h.agent("Scout", listRefunds("ann"), done("ok"));
        HarnessExecutor e = h.executor(InvokerHarness.SCRIPT);
        e.initialize();
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of(), InvokerCredentials.none()))
                .isInstanceOf(MissingInvokerCredentialException.class);

        e.executeWorkflow("Main", Map.of(), InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1");
    }

    @Test
    @Tag("CC-V6.1")
    void aScriptThatNeverAsksForInvokerCredentialsRunsAsBeforeWithOrWithoutThem() {
        String plain =
                "agent Scout { model: \"m\" system: \"You are Scout.\" }\nworkflow Main() { delegate \"hi\" to Scout -> x }";
        HarnessExecutor a = h.executor(plain);
        a.initialize();
        a.executeWorkflow("Main", Map.of());
        HarnessExecutor b = h.executor(plain);
        b.initialize();
        b.executeWorkflow("Main", Map.of(), InvokerCredentials.none());
        HarnessExecutor c = h.executor(plain);
        c.initialize();
        c.executeWorkflow("Main", Map.of(), null);

        assertThat(a.getContext().getVariable("x")).isEqualTo("ok");
        assertThat(b.getContext().getVariable("x")).isEqualTo("ok");
        assertThat(c.getContext().getVariable("x")).isEqualTo("ok");
    }

    @Test
    @Tag("CC-V3.2")
    void parallelBranchesActAsTheSamePerson() {
        h.agent("Scout", listRefunds("ann"), done("a"));
        h.agent("Scout2", listRefunds("bob"), done("b"));

        run(
                TWO_AGENTS
                        + "workflow Main() { parallel { delegate \"a\" to Scout -> a  delegate \"b\" to Scout2 -> b } }",
                InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1", "Bearer t1");
    }

    @Test
    @Tag("CC-V3.2")
    void aBroadcastActsAsTheSamePerson() {
        h.agent("Scout", listRefunds("ann"), done("a"));
        h.agent("Scout2", listRefunds("bob"), done("b"));

        run(
                TWO_AGENTS + "workflow Main() { broadcast \"go\" to [Scout, Scout2] -> r }",
                InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1", "Bearer t1");
    }

    @Test
    @Tag("CC-V3.2")
    void aStepWithATimeoutActsAsTheSamePerson() {
        h.agent("Scout", listRefunds("ann"), done("a"));

        run(
                TWO_AGENTS + "workflow Main() { delegate \"a\" to Scout -> a timeout 30s }",
                InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1");
    }

    @Test
    @Tag("CC-V3.2")
    void aParallelForEachActsAsTheSamePerson() {
        h.agent("Lead", "```json\n{\"items\": [{\"note\": \"x\"}, {\"note\": \"y\"}]}\n```");
        h.agent("Scout", listRefunds("ann"), done("a"), listRefunds("bob"), done("b"));

        run(
                TWO_AGENTS
                        + """
                agent Lead { model: "m" system: "You are Lead." output_schema: { items: list<{ note: string }> } }
                workflow Main() {
                    delegate "plan" to Lead -> plan
                    parallel for each it in plan.items { delegate "look at {it.note}" to Scout -> r }
                }
                """,
                InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1", "Bearer t1");
    }

    @Test
    @Tag("CC-V3.3")
    void aWorkflowThatIsCalledKeepsTheRunsInvoker() {
        h.agent("Scout", listRefunds("ann"), done("a"));

        run(
                TWO_AGENTS
                        + "workflow Sub() { delegate \"a\" to Scout -> inner }\nworkflow Main() { call Sub() -> r }",
                InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.authorizations()).containsExactly("Bearer t1");
    }

    private static final String TWO_AGENTS =
            """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
            agent Scout2 { model: "m"  system: "You are Scout2."  tools: [Refunds] }
            """;

    @Test
    @Tag("CC-V5.1")
    void theTokenIsNeverInWhatTheModelSeesEvenWhenTheApiEchoesIt() {
        h.agent("Scout", listRefunds("ann"), done("ok"));
        h.respond(r -> new MockResponse().setBody("you are " + r.getHeader("Authorization")));

        run(
                InvokerHarness.SCRIPT,
                InvokerCredentials.of(Map.of("token", "Bearer SECRETVALUE-9f3a")));

        assertThat(h.modelSaw).isNotEmpty();
        assertThat(String.join("\n", h.modelSaw))
                .doesNotContain("SECRETVALUE")
                .contains("you are ***");
    }

    @Test
    @Tag("CC-V2.1")
    void anAgentThatPostsActsAsThePersonToo() {
        h.agent(
                "Scout",
                call(
                        "Refunds",
                        "{\"endpoint\": \"/refunds\", \"method\": \"POST\", \"body\": {\"amount\": 5}}"),
                done("created"));

        run(InvokerHarness.SCRIPT, InvokerCredentials.of(Map.of("token", "Bearer t1")));

        assertThat(h.requests.get(0).getMethod()).isEqualTo("POST");
        assertThat(h.requests.get(0).getBody().readUtf8()).isEqualTo("{\"amount\":5}");
        assertThat(h.authorizations()).containsExactly("Bearer t1");
        assertThat(List.of(h.requests.size())).containsExactly(1);
    }
}
