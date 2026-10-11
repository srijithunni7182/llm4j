package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The operator's commands on a ladder, against a real store written by real runs. */
@Tag("L8")
class LadderCommandsTest {

    static final String SCRIPT = """
            import "loom/autonomy"
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            ladder L {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 3 and history.rate(agrees) >= 0.8
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 6 and history.rate(agrees) >= 0.8
                    slip to suggest when history.last(3).count(dangerous) >= 2
                }
                climbing needs approval from risk-owner
            }
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                group cases by: tier
                remember: amount, tier
                dangerous mistake: propose approve, person decides reject
                ask: support-lead
                ladder: L
                ceiling: act
                when the agent changes: start over
            }
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                decide Refund -> verdict
                note "case {ticket}: {verdict}"
            }
            """;

    @TempDir
    Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    File script;
    Path store;
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
    Function<Map<String, String>, String> agent = f -> "approve";
    Function<Map<String, String>, String> person = f -> "approve";
    private int runs;
    private static final Pattern LINE = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*) = (.*)$");

    static Map<String, String> fields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LINE.matcher(text);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), SCRIPT);
        store = dir.resolve("store");
    }

    String out() { return outBytes.toString(); }

    String err() { return errBytes.toString(); }

    WeaveEnv env() {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                var messages = request.getMessages();
                String choice = agent.apply(fields(messages.get(messages.size() - 1).getContent()));
                return LLMResponse.builder().content("```json\n{\"choice\": \"" + choice + "\", \"reasoning\": \"because\", \"confidence\": 0.9}\n```").model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        return new WeaveEnv(m -> client, question -> person.apply(fields(question)), new PrintStream(outBytes, true), new PrintStream(errBytes, true), clock, d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), System::getenv);
    }

    int runCase(String tier, int amount) {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("ticket", "T-" + amount + "-" + (runs + 1));
        in.put("tier", tier);
        in.put("amount", String.valueOf(amount));
        in.put("reason", "damaged");
        in.put("customer_since", "2020");
        Path run = dir.resolve("runs").resolve("case-" + (++runs));
        int code = WeaveCLI.run(script, null, "Triage", in, null, null, null, null, run, store, false, false, null, null, env());
        clock.advance(Duration.ofHours(1));
        return code;
    }

    @Test
    void statusSaysWhereEachScopeStandsAndWhatIsMissingForTheNextClimb() throws Exception {
        for (int i = 0; i < 2; i++) assertThat(runCase("gold", 10 + i)).isZero();
        outBytes.reset();
        assertThat(AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err)).isZero();
        String text = out();
        assertThat(text).contains("Refund   ladder L, may climb as far as act").contains("scope gold   rung watch").contains("2 answered cases in this epoch")
                .contains("towards suggest:").contains("history.count >= 3").contains("[2 vs 3]").contains("not met");

        for (int i = 0; i < 2; i++) runCase("gold", 20 + i);
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("open promotion proposal: to suggest").contains("waiting for risk-owner");

        outBytes.reset();
        assertThat(AutonomyCommands.status(store, null, null, true, clock, new PrintStream(outBytes, true), System.err)).isZero();
        Object json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(out(), Object.class);
        Map<?, ?> decision = (Map<?, ?>) ((List<?>) json).get(0);
        assertThat(decision.get("decision")).isEqualTo("Refund");
        assertThat(decision.get("ladder")).isEqualTo("L");
        Map<?, ?> scope = (Map<?, ?>) ((List<?>) decision.get("scopes")).get(0);
        assertThat(scope.get("rung")).isEqualTo("watch");
        assertThat(scope.get("openProposal")).isEqualTo("suggest");
    }

    @Test
    void onlyTheNamedApproverApprovesAndTheApprovalMovesTheRungAndIsAudited() throws Exception {
        for (int i = 0; i < 4; i++) runCase("gold", 10 + i);
        assertThat(AutonomyCommands.decide(store, "Refund", "gold", true, "fine", null, "someone-else", env())).isEqualTo(2);
        assertThat(err()).contains("only risk-owner may approve");
        errBytes.reset();
        assertThat(AutonomyCommands.decide(store, "Refund", "gold", true, "numbers look right", null, "risk-owner", env())).isZero();
        assertThat(out()).contains("Approved: gold moved to suggest.");
        outBytes.reset();
        runCase("gold", 99);
        outBytes.reset();
        AutonomyCommands.history(store, null, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("(start) -> watch").contains("watch -> suggest").contains("approved by risk-owner: numbers look right");
        assertThat(Files.readAllLines(store.resolve("autonomy/audit.jsonl")).get(0)).contains("\"event\":\"promotion_approved\"").contains("\"decision\":\"Refund\"");
    }

    @Test
    void aHandMoveNeedsForceToGoAboveTheEvidenceAndNeverToComeDown() throws Exception {
        runCase("gold", 10);
        assertThat(AutonomyCommands.move(store, "Refund", "gold", null, true, false, "drill", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("not supported by the evidence").contains("--force");
        errBytes.reset();
        assertThat(AutonomyCommands.move(store, "Refund", "gold", "suggest", true, true, "incident", null, "ops", env())).isZero();
        assertThat(out()).contains("Moved gold from watch to suggest (FORCED");
        outBytes.reset();
        assertThat(AutonomyCommands.move(store, "Refund", "gold", "watch", false, false, "model changed", null, "ops", env())).isZero();
        assertThat(out()).contains("Moved gold from suggest to watch.");
        assertThat(AutonomyCommands.move(store, "Refund", "gold", "nowhere", true, true, "x", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("one of the rungs: watch, suggest, act");
        assertThat(AutonomyCommands.move(store, "Refund", "nobody", null, true, false, "x", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("no such scope");
    }

    @Test
    void anOutcomeIsRecordedWithItsValueAndAFreezeStopsTheAutonomousRungs() throws Exception {
        for (int i = 0; i < 3; i++) runCase("gold", 10 + i);
        assertThat(AutonomyCommands.outcome(store, "Refund", firstCaseId(), "views", "5400", "a week on", null, env())).isZero();
        assertThat(out()).contains("Recorded:").contains("views = 5400");
        assertThat(AutonomyCommands.outcome(store, "Refund", "no-such-case", "reversed", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("no case no-such-case");

        outBytes.reset();
        assertThat(AutonomyCommands.freeze(store, "Refund", true, "incident 42", env())).isZero();
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("FROZEN");
        assertThat(AutonomyCommands.freeze(store, "Refund", false, "resolved", env())).isZero();
    }

    private String firstCaseId() {
        return new io.github.llm4j.loom.ladder.LadderEngine(
                io.github.llm4j.loom.ladder.LadderPolicy.of(loaded(), loaded().getDecisions().get(0)), AutonomySupport.ledger(store), clock).cases().get(0).id();
    }

    private io.github.llm4j.loom.ast.LoomScript loaded() {
        try {
            return new io.github.llm4j.loom.execution.LoomLoader().load(script.getAbsolutePath());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---------- weave ladder ----------

    @Test
    void describeSaysInSentencesWhatTheLadderRequires() {
        assertThat(LadderCliCommand.describe(script, null, env())).isZero();
        String text = out();
        assertThat(text).contains("Ladder L").contains("Used by decision Refund").contains("Rung watch: a person answers without seeing the agent's proposal.")
                .contains("the number of cases is at least 3").contains("Moving up needs approval from risk-owner.");
        errBytes.reset();
        assertThat(LadderCliCommand.describe(script, "Nope", env())).isEqualTo(2);
        assertThat(err()).contains("no ladder named Nope").contains("Ladders: L");
    }

    @Test
    void testTriesTheLadderOnARecordedHistoryAndWritesNothing() throws Exception {
        for (int i = 0; i < 5; i++) runCase("gold", 10 + i);
        runCase("basic", 30);
        long before = Files.walk(store).filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        outBytes.reset();
        assertThat(LadderCliCommand.test(script, "Refund", store, clock, new PrintStream(outBytes, true), System.err)).isZero();
        String text = out();
        assertThat(text).contains("(nothing is written)").contains("scope gold   would hold rung suggest").contains("scope basic   would hold rung watch")
                .contains("history.count >= 6").contains("[5 vs 6]");
        long after = Files.walk(store).filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        assertThat(after).isEqualTo(before);
        assertThat(LadderCliCommand.test(script, "Nothing", store, clock, new PrintStream(outBytes, true), new PrintStream(errBytes, true))).isEqualTo(2);
    }

    @Test
    void theLadderCommandIsRegistered() {
        CommandLine cli = WeaveCLI.commandLine();
        assertThat(cli.getSubcommands()).containsKeys("ladder", "autonomy");
        assertThat(cli.getSubcommands().get("ladder").getSubcommands()).containsKeys("describe", "test");
    }
}
