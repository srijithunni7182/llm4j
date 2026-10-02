package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.Case;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

/** The operator's commands on the ladders, against a real store written by real runs (spec loom-earned-autonomy R5). */
class AutonomyCommandsTest {

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
        Files.writeString(script.toPath(), io.github.llm4j.loom.autonomy.Scripts2Access.refund("tell Nothing when trust changes".isEmpty() ? "" : ""));
        store = dir.resolve("store");
    }

    String out() {
        return outBytes.toString();
    }

    String err() {
        return errBytes.toString();
    }

    WeaveEnv env() {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                var messages = request.getMessages();
                String last = messages.get(messages.size() - 1).getContent();
                String choice = agent.apply(fields(last));
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
        in.put("ticket", "T-" + amount);
        in.put("tier", tier);
        in.put("amount", String.valueOf(amount));
        in.put("reason", "damaged");
        in.put("customer_since", "2020");
        Path run = dir.resolve("runs").resolve("case-" + (++runs));
        int code = WeaveCLI.run(script, null, "Triage", in, null, null, null, null, run, store, false, false, null, null, env());
        clock.advance(Duration.ofHours(1));
        return code;
    }

    int cmd(String... args) {
        outBytes.reset();
        errBytes.reset();
        CommandLine cli = new CommandLine(new WeaveCLI());
        cli.addSubcommand(new AutonomyCommands());
        cli.setOut(new java.io.PrintWriter(outBytes, true));
        cli.setErr(new java.io.PrintWriter(errBytes, true));
        return cli.execute(args);
    }

    @Test
    @Tag("EA-V5.1")
    void statusPrintsEveryFieldForEveryScopeIncludingWhatIsMissingForTheNextStep() throws Exception {
        for (int i = 0; i < 6; i++) assertThat(runCase("gold", 10 + i)).isZero();
        for (int i = 0; i < 2; i++) assertThat(runCase("basic", 50 + i)).isZero();

        assertThat(WeaveCLI.class).isNotNull();
        outBytes.reset();
        assertThat(AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err)).isZero();
        String text = out();

        assertThat(text).contains("Refund").contains("scope gold   level suggest").contains("scope basic   level watch");
        assertThat(text).contains("evidence: 5 blind cases of the latest 20").contains("agreement 100.0%").contains("lower bound").contains("dangerous mistakes 0.0%")
                .contains("coverage 100.0%").contains("unusable proposals 0");
        assertThat(text).contains("towards act:").contains("cases: short (5 cases, needs 8 cases)");
        assertThat(text).contains("towards suggest:").contains("cases: short (2 cases, needs 5 cases)");
        assertThat(text).contains("last change:").contains("watch -> suggest (promoted,");

        outBytes.reset();
        assertThat(AutonomyCommands.status(store, null, null, true, clock, new PrintStream(outBytes, true), System.err)).isZero();
        Object json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(out(), Object.class);
        assertThat(json).isInstanceOf(List.class);
        Map<?, ?> decision = (Map<?, ?>) ((List<?>) json).get(0);
        assertThat(decision.get("decision")).isEqualTo("Refund");
        assertThat(((List<?>) decision.get("scopes"))).hasSize(2);
    }

    @Test
    @Tag("EA-V5.2")
    @Tag("EA-V5.6")
    void historyListsTheChangesInOrderAndEveryChangeIsAuditedWithItsFigures() throws Exception {
        for (int i = 0; i < 6; i++) runCase("gold", 10 + i);
        assertThat(AutonomyCommands.move(store, "Refund", "gold", null, false, false, "an incident", null, null, env())).isZero();
        outBytes.reset();
        AutonomyCommands.history(store, null, new PrintStream(outBytes, true), System.err);

        String text = out();
        assertThat(text).contains("scope gold").contains("(start) -> watch").contains("watch -> suggest").contains("suggest -> watch").contains("[set").contains("an incident");
        assertThat(text.indexOf("watch -> suggest")).isLessThan(text.indexOf("suggest -> watch"));

        List<String> audit = Files.readAllLines(store.resolve("autonomy/audit.jsonl"));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("\"event\":\"level_changed\"").contains("\"decision\":\"Refund\"").contains("\"scope\":\"gold\"").contains("\"rule\":\"by hand\"").contains("\"figures\"");
    }

    @Test
    @Tag("EA-V5.3")
    @Tag("EA-V8.5")
    void promotingBeyondTheCeilingOrTheEvidenceNeedsForceIsRecordedAsForcedAndShownAsForcedUntilEvidenceCatchesUp() throws Exception {
        Files.writeString(script.toPath(), Files.readString(script.toPath()).replace("judge on the latest 20 cases", "judge on the latest 20 cases\n check 50% of cases with a person who doesn't see the proposal"));
        runCase("gold", 10);
        assertThat(AutonomyCommands.move(store, "Refund", "gold", "act", true, false, "trust me", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("not supported by the evidence").contains("--force");

        outBytes.reset();
        assertThat(AutonomyCommands.move(store, "Refund", "gold", "act", true, true, "emergency", null, null, env())).isZero();
        assertThat(out()).contains("FORCED");
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("level act").contains("FORCED: set by hand, not yet supported by the evidence");
        assertThat(Files.readString(store.resolve("autonomy/audit.jsonl"))).contains("\"forced\":true");

        // the evidence catches up: more agreeing blind cases, and the mark goes
        person = f -> "approve";
        for (int i = 0; i < 60; i++) runCase("gold", 100 + i);
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).doesNotContain("FORCED");
    }

    @Test
    @Tag("EA-V5.3")
    void everyCommandThatChangesALadderRequiresAReason() throws Exception {
        runCase("gold", 10);
        for (String[] args : new String[][] {{"promote", "Refund", "--scope", "gold"}, {"demote", "Refund", "--scope", "gold"}, {"freeze", "Refund"}, {"unfreeze", "Refund"},
                {"approve", "Refund", "--scope", "gold"}, {"reject", "Refund", "--scope", "gold"}}) {
            List<String> full = new ArrayList<>(List.of("autonomy", args[0], store.toString()));
            full.addAll(List.of(args).subList(1, args.length));
            assertThat(cmd(full.toArray(String[]::new))).as(args[0]).isEqualTo(2);
            assertThat(err() + out()).as(args[0]).contains("--reason");
        }
    }

    @Test
    @Tag("EA-V5.4")
    @Tag("EA-V3.11")
    void freezeStopsActForCasesThatBeginAfterItAndUnfreezeRestoresTheEarnedLevel() throws Exception {
        runCase("gold", 10);
        setLevel("gold", Level.ACT);
        List<String> askedBefore = new ArrayList<>();
        person = f -> {
            askedBefore.add("asked");
            return "approve";
        };

        assertThat(AutonomyCommands.freeze(store, "Refund", true, "incident 42", env())).isZero();
        runCase("gold", 99);
        assertThat(askedBefore).as("at most suggest while frozen: a person was asked").hasSize(1);
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("FROZEN").contains("incident 42").contains("running at suggest");

        assertThat(AutonomyCommands.freeze(store, "Refund", false, "all clear", env())).isZero();
        askedBefore.clear();
        runCase("gold", 98);
        assertThat(askedBefore).as("back to the level it earned").isEmpty();
    }

    /** Puts a scope at a level, as the ladder would have after earning it (the identity is the script's, so nothing starts a new epoch). */
    private void setLevel(String scope, Level level) throws Exception {
        var levels = new io.github.llm4j.loom.autonomy.FileLevelStore(store.resolve("autonomy"));
        var old = levels.get("Refund", scope).orElseThrow();
        var loaded = new io.github.llm4j.loom.execution.LoomLoader().load(script.getAbsolutePath());
        String identity = io.github.llm4j.loom.autonomy.AgentIdentity.of(loaded, loaded.getDecisions().get(0), dir);
        levels.compareAndSet("Refund", scope, old, new io.github.llm4j.loom.autonomy.LevelState(level, old.epoch(), identity, false, old.since(), "set up by the test", old.version() + 1));
    }

    private String levelOf(String scope) {
        return new io.github.llm4j.loom.autonomy.FileLevelStore(store.resolve("autonomy")).get("Refund", scope).orElseThrow().level().word();
    }

    @Test
    @Tag("EA-V5.5")
    void aReversalOfAVerdictTheAgentMadeCountsAgainstItAndOneOfAPersonsDoesNotAndAnUnknownCaseIsAnError() throws Exception {
        Files.writeString(script.toPath(), Files.readString(script.toPath()).replace("moving up is automatic", "drop to suggest when 2 reversals in 20 cases\n moving up is automatic"));
        for (int i = 0; i < 4; i++) runCase("gold", 10 + i);
        setLevel("gold", Level.ACT);
        for (int i = 0; i < 6; i++) runCase("gold", 20 + i);
        assertThat(levelOf("gold")).isEqualTo("act");
        List<Case> cases = new io.github.llm4j.loom.autonomy.FileLedger(store.resolve("autonomy")).cases("Refund");
        List<Case> byAgent = cases.stream().filter(Case::decidedByAgent).toList();
        List<Case> byPerson = cases.stream().filter(c -> !c.decidedByAgent()).toList();
        assertThat(byAgent).hasSizeGreaterThan(3);

        assertThat(AutonomyCommands.outcome(store, "Refund", byPerson.get(0).id(), "reversed", null, null, env())).isZero();
        assertThat(levelOf("gold")).as("a person's verdict reversed does not count against the agent").isEqualTo("act");

        assertThat(AutonomyCommands.outcome(store, "Refund", byAgent.get(0).id(), "reversed", "chargeback", null, env())).isZero();
        assertThat(levelOf("gold")).as("one reversal").isEqualTo("act");
        outBytes.reset();
        assertThat(AutonomyCommands.outcome(store, "Refund", byAgent.get(1).id(), "reversed", null, null, env())).isZero();
        assertThat(levelOf("gold")).as("two reversals in the last 20").isEqualTo("suggest");
        assertThat(out()).contains("moved from act to suggest");

        assertThat(AutonomyCommands.outcome(store, "Refund", "no-such-case", "reversed", null, null, env())).isEqualTo(2);
        assertThat(err()).contains("there is no case no-such-case");
    }

    @Test
    @Tag("EA-V4.4")
    void aCaseWithNoVerdictForTooLongIsListedAsStale() throws Exception {
        person = f -> {
            throw new io.github.llm4j.loom.runtime.RunSuspended("x", "waiting");
        };
        assertThat(runCase("gold", 10)).isEqualTo(4);
        clock.advance(Duration.ofDays(9));
        outBytes.reset();
        AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), System.err);
        assertThat(out()).contains("stale: 1 case(s) with no verdict after 7 days");
    }

    @Test
    @Tag("EA-V5.8")
    void theCommandsWorkWhileRunsAreWritingAndNeverSeeAHalfWrittenStore() throws Exception {
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 25; i++) runCase("gold", 10 + i);
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        writer.start();
        int reads = 0;
        while (writer.isAlive() || reads < 3) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try {
                if (Files.exists(store.resolve("autonomy/Refund/script"))) AutonomyCommands.status(store, "Refund", null, true, clock, new PrintStream(sink, true), new PrintStream(sink, true));
                if (Files.exists(store.resolve("autonomy/Refund/script"))) assertThat(sink.toString()).contains("\"decision\"");
            } catch (Throwable t) {
                failures.add(t);
            }
            reads++;
            if (reads > 500) break;
        }
        writer.join();
        assertThat(failures).isEmpty();
        assertThat(new io.github.llm4j.loom.autonomy.FileLedger(store.resolve("autonomy")).unreadable("Refund")).isZero();
    }

    @Test
    @Tag("EA-V4.6")
    void theNumberStatusPrintsIsTheNumberThePromotionWasDecidedOn() throws Exception {
        boolean[] agrees = {false, false, true, true, true, true, true, true, true, true, true, true};
        double floor = 50;
        int promotedAt = -1;
        for (int i = 0; i < agrees.length; i++) {
            boolean agree = agrees[i];
            person = f -> agree ? "approve" : "escalate";
            runCase("gold", 10 + i);
            outBytes.reset();
            AutonomyCommands.status(store, "Refund", null, true, clock, new PrintStream(outBytes, true), System.err);
            Map<?, ?> scope = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(out(), List.class)).get(0)).get("scopes")).get(0);
            double lowerBound = ((Number) scope.get("lowerBound")).doubleValue() * 100;
            int cases = ((Number) scope.get("cases")).intValue();
            boolean promoted = "suggest".equals(scope.get("level"));
            // the rule is: after 5 cases, a lower bound of 50% (the first levels' rule in the small ladder)
            assertThat(promoted).as("after case " + (i + 1) + ": " + cases + " cases, lower bound " + lowerBound).isEqualTo(cases >= 5 && lowerBound >= floor);
            if (promoted && promotedAt < 0) promotedAt = i;
            if (promoted) break;
        }
        assertThat(promotedAt).as("it was promoted at some point, and not before the number crossed the floor").isGreaterThan(4);
    }
}
