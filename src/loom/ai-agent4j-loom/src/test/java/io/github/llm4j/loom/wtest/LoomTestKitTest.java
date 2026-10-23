package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Java twin of {@code weave test}: the same cases, the same answers. */
class LoomTestKitTest {

    @TempDir Path dir;

    private Path script() throws Exception {
        Path p = dir.resolve("main.loom");
        Files.writeString(p, Wtest.TRIAGE);
        return p;
    }

    @Test
    void aWorkflowIsRunWithScriptedAnswersAndAsserted() throws Exception {
        LoomRun run =
                LoomTest.of(script())
                        .classifier("Intent", Map.of("kind", "order_status", "urgent", "no"))
                        .model("Replier", "Your parcel shipped yesterday.")
                        .run("Main", Map.of("message", "where is my parcel?"));

        LoomRunAssert.assertThat(run)
                .completed()
                .returned("Your parcel shipped yesterday.")
                .called("Replier", 1)
                .called("Escalator", 0)
                .ran("classify Intent", "delegate Replier", "note")
                .neverRan("delegate Escalator")
                .noEffects()
                .nobodyAsked();
        assertThat(run.variable("intent.kind")).isEqualTo("order_status");
    }

    @Test
    void anAssertionFailureSaysExpectedAndActual() throws Exception {
        LoomRun run =
                LoomTest.of(script())
                        .classifier("Intent", Map.of("kind", "refund", "urgent", "yes"))
                        .model("Escalator", "Refund wanted.")
                        .run("Main", Map.of("message", "money back"));
        assertThatThrownBy(() -> LoomRunAssert.assertThat(run).completed().called("Replier", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calls.Replier: expected 1, got 0");
    }

    @Test
    void anUnscriptedCallFailsTheRunAndNamesTheAgent() throws Exception {
        LoomRun run =
                LoomTest.of(script())
                        .classifier("Intent", Map.of("kind", "order_status", "urgent", "no"))
                        .run("Main", Map.of("message", "x"));
        assertThatThrownBy(() -> LoomRunAssert.assertThat(run).completed())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Replier");
    }

    @Test
    void failuresCanBeInjected() throws Exception {
        LoomRun run =
                LoomTest.of(script())
                        .classifier("Intent", Map.of("kind", "order_status", "urgent", "no"))
                        .model("Replier", "ok")
                        .failing("Replier", LoomTest.Failure.always("server_error"))
                        .run("Main", Map.of("message", "x"));
        assertThat(run.status()).isEqualTo("failed");
    }

    @Test
    void theSameCaseInYamlAndInJavaGivesTheSameAnswer() throws Exception {
        Path s = script();
        Files.createDirectories(dir.resolve("tests"));
        Path f = dir.resolve("tests/main.test.yaml");
        Files.writeString(
                f,
                """
                script: ../main.loom
                workflow: Main
                cases:
                  - name: parcel
                    input: { message: "where?" }
                    models:
                      Intent: { kind: order_status, urgent: "no" }
                      Replier: "Shipped."
                    expect:
                      result: "Shipped."
                      calls: { Replier: 1 }
                """);
        List<LoomTest.NamedCase> cases = LoomTest.cases(f);
        assertThat(cases).extracting(LoomTest.NamedCase::name).containsExactly("parcel");
        cases.get(0).run();

        LoomRun run =
                LoomTest.of(s)
                        .classifier("Intent", Map.of("kind", "order_status", "urgent", "no"))
                        .model("Replier", "Shipped.")
                        .run("Main", Map.of("message", "where?"));
        LoomRunAssert.assertThat(run).completed().returned("Shipped.").called("Replier", 1);
    }

    @Test
    void aFailingYamlCaseThrowsWithEveryDifference() throws Exception {
        script();
        Files.createDirectories(dir.resolve("tests"));
        Path f = dir.resolve("tests/main.test.yaml");
        Files.writeString(
                f,
                """
                script: ../main.loom
                workflow: Main
                cases:
                  - name: wrong
                    input: { message: "where?" }
                    models:
                      Intent: { kind: order_status, urgent: "no" }
                      Replier: "Shipped."
                    expect:
                      result: "Delayed."
                """);
        assertThatThrownBy(() -> LoomTest.cases(f).get(0).run())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("case \"wrong\" failed")
                .hasMessageContaining("result");
    }

    @Test
    void testFilesThatDoNotFitTheScriptAreListedUpFront() throws Exception {
        script();
        Files.createDirectories(dir.resolve("tests"));
        Path f = dir.resolve("tests/main.test.yaml");
        Files.writeString(
                f,
                """
                script: ../main.loom
                workflow: Main
                cases:
                  - name: typo
                    input: { message: "x" }
                    models: { Replyer: "hi" }
                """);
        assertThatThrownBy(() -> LoomTest.cases(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Replyer");
    }
}
