package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real run of {@code weave run}: the agent behind a ladder decision is changed, and the new agent
 * starts on the rung the replay of the old one's cases supports.
 */
@Tag("L7")
class LadderTestOnPastCasesCliTest {

    static final String SCRIPT =
            """
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest { mode: confirm  climb when history.count >= 3 and history.rate(agrees) >= 0.7 }
                rung act { mode: autonomous  climb when history.count >= 5 and history.rate(agrees) >= 0.9  slip when history.last(5).rate(agrees) < 0.5  sample 10% blind }
                climbing is automatic
            }
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                group cases by: tier
                remember: amount, reason, customer_since
                ask: support-lead
                ladder: L
                when the agent changes: test it on past cases
            }
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                decide Refund -> verdict
                note "{verdict}"
            }
            """;

    @TempDir Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
    File script;
    Path store;
    int runs;
    int asked;

    WeaveEnv env() {
        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String system = request.getMessages().get(0).getContent();
                        String last =
                                request.getMessages()
                                        .get(request.getMessages().size() - 1)
                                        .getContent();
                        int amount =
                                Integer.parseInt(
                                        AutonomyCommandsTest.fields(last)
                                                .getOrDefault("amount", "0"));
                        // the old agent approves everything; the new, stricter one does what the
                        // people do
                        String choice =
                                system.contains("stricter") && amount >= 100 ? "reject" : "approve";
                        return LLMResponse.builder()
                                .content(
                                        "```json\n{\"choice\": \""
                                                + choice
                                                + "\", \"reasoning\": \"r\", \"confidence\": 0.9}\n```")
                                .model("m")
                                .tokenUsage(10, 5, 15)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }
                };
        return new WeaveEnv(
                m -> client,
                q -> {
                    asked++;
                    return Integer.parseInt(AutonomyCommandsTest.fields(q).get("amount")) < 100
                            ? "approve"
                            : "reject";
                },
                new PrintStream(outBytes, true),
                new PrintStream(errBytes, true),
                clock,
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                System::getenv);
    }

    void runCases(int from, int to) {
        for (int i = from; i < to; i++) {
            Map<String, String> in = new LinkedHashMap<>();
            in.put("ticket", "T-" + i);
            in.put("tier", "gold");
            in.put("amount", String.valueOf(20 + i * 20));
            in.put("reason", "damaged");
            in.put("customer_since", "2020");
            assertThat(
                            WeaveCLI.run(
                                    script,
                                    null,
                                    "Triage",
                                    in,
                                    null,
                                    null,
                                    null,
                                    null,
                                    dir.resolve("runs").resolve("c" + (++runs)),
                                    store,
                                    false,
                                    false,
                                    null,
                                    null,
                                    env()))
                    .isZero();
            clock.advance(Duration.ofHours(1));
        }
    }

    LevelLog.Standing standing() throws Exception {
        var loaded = new io.github.llm4j.loom.execution.LoomLoader().load(script.getAbsolutePath());
        var policy = LadderPolicy.of(loaded, loaded.getDecisions().get(0));
        return new LevelLog("Refund", policy.rungs(), new FileLedger(store.resolve("autonomy")))
                .current("gold")
                .orElseThrow();
    }

    @Test
    void aChangedPromptInARealRunStartsTheNewAgentOnTheRungItsReplayEarnsAndNotOnTheFirst()
            throws Exception {
        script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), SCRIPT);
        store = dir.resolve("store");
        runCases(0, 12);
        assertThat(standing().rungName())
                .as("the old agent agreed with the people on the small amounts and earned suggest")
                .isEqualTo("suggest");

        // the prompt is changed: a new agent
        Files.writeString(
                script.toPath(), SCRIPT.replace("You are Triager.", "You are a stricter Triager."));
        int ledgerBefore = Files.readAllLines(store.resolve("autonomy/Refund/ledger.jsonl")).size();
        runCases(12, 13);

        LevelLog.Standing now = standing();
        assertThat(now.epoch()).isEqualTo(2);
        assertThat(now.reason()).contains("test it on past cases");
        assertThat(now.rungName())
                .as(
                        "its replay over the old agent's blind cases agreed with the people every time: it keeps suggest and does not start over on watch")
                .isEqualTo("suggest");
        assertThat(Files.list(store.resolve("autonomy/Refund/replays")).count())
                .as("the replay kept its own directory")
                .isEqualTo(1);
        // nothing but the case itself and the new epoch was added to the ledger by the replay
        assertThat(
                        Files.readAllLines(store.resolve("autonomy/Refund/ledger.jsonl")).size()
                                - ledgerBefore)
                .isLessThanOrEqualTo(6);
    }
}
