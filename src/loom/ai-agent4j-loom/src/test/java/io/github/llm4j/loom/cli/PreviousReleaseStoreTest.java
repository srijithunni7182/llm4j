package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.AgentIdentity;
import io.github.llm4j.loom.autonomy.Case;
import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.FileLevelStore;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verification C5 and G4, against a store the previous release wrote.
 *
 * <p>{@code src/test/resources/previous-release/store/<scenario>} holds a real store (ledger, level store, audit, run journals) written by the release before
 * this work running 40 to 90 cases of a refund decision, and, in numbered files, what each operator command printed on it, in order (status, history, outcome,
 * promote, demote, freeze, approve, reject, replay). This test opens a copy with the code here and runs the same commands.
 */
@Tag("L7")
class PreviousReleaseStoreTest {

    static final Path FIXTURE = Path.of("src/test/resources/previous-release/store");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern LINE = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*) = (.*)$");

    @TempDir
    Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
    Path store;
    File script;

    static Map<String, String> fields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LINE.matcher(text);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    WeaveEnv env() {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String last = request.getMessages().get(request.getMessages().size() - 1).getContent();
                int amount = Integer.parseInt(fields(last).getOrDefault("amount", "0"));
                String choice = amount % 7 == 0 ? "reject" : "approve";
                return LLMResponse.builder().content("```json\n{\"choice\": \"" + choice + "\", \"reasoning\": \"r\", \"confidence\": 0.9}\n```").model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        return new WeaveEnv(m -> client, q -> "approve", new PrintStream(outBytes, true), new PrintStream(errBytes, true), clock, d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), System::getenv);
    }

    /** A copy of the previous release's store with its temp directory made this one's, ready to use. */
    void open(String scenario) throws Exception {
        Path from = FIXTURE.resolve(scenario);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path t = dir.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    String name = p.getFileName().toString();
                    boolean text = name.endsWith(".jsonl") || name.endsWith(".json") || name.endsWith(".loom") || name.equals("script") || name.equals("commands.txt");
                    if (text) Files.writeString(t, Files.readString(p).replace("@ROOT@", dir.toString()));
                    else Files.copy(p, t);
                }
            }
        }
        store = dir.resolve("store");
        script = dir.resolve("refund.loom").toFile();
        // the commands were run right after the last case, which the maker followed with three hours
        Instant last = new FileLedger(store.resolve("autonomy")).records("Refund").stream().map(io.github.llm4j.loom.autonomy.Rec::at).max(Instant::compareTo).orElseThrow();
        clock.set(last.plus(java.time.Duration.ofHours(3)));
    }

    // ---- C5 -------------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"automatic", "approver"})
    void aStoreThePreviousReleaseWroteReadsWithEveryCaseAndLevelAndTheFirstEvaluationMovesNoOne(String scenario) throws Exception {
        open(scenario);
        Path autonomy = store.resolve("autonomy");
        FileLedger ledger = new FileLedger(autonomy);
        List<Case> cases = ledger.cases("Refund");
        assertThat(cases).isNotEmpty();
        assertThat(ledger.unreadable("Refund")).as("nothing unreadable").isZero();
        assertThat(cases).allSatisfy(c -> assertThat(c.verdict()).as(c.id()).isNotNull());

        // the levels the previous release kept
        Map<String, LevelState> before = new FileLevelStore(autonomy).scopes("Refund");
        assertThat(before).isNotEmpty();
        var loaded = new LoomLoader().load(script.getAbsolutePath());
        var decision = loaded.getDecisions().get(0);
        assertThat(decision.isLadderFromTrust()).isTrue();
        LadderPolicy policy = LadderPolicy.of(loaded, decision);
        LadderEngine engine = new LadderEngine(policy, ledger, clock);

        // the new engine reads each scope where the previous one left it
        before.forEach((scope, state) -> {
            var standing = engine.log().current(scope).orElseThrow();
            assertThat(standing.rungName()).as(scope).isEqualTo(state.level().word());
            assertThat(standing.epoch()).as(scope).isEqualTo(state.epoch());
            assertThat(standing.identity()).as(scope).isEqualTo(state.identity());
        });
        // and the first evaluation, which looks at every scope, moves none of them
        assertThat(engine.reevaluate()).as("the upgrade alone moves no one").isEmpty();
        before.forEach((scope, state) -> assertThat(engine.log().current(scope).orElseThrow().rungName()).isEqualTo(state.level().word()));
        // and the agent is the same agent: nothing starts a new epoch because the engine changed
        String identity = AgentIdentity.of(loaded, decision, dir);
        before.forEach((scope, state) -> assertThat(engine.standing(scope, identity, "m").epoch()).as(scope).isEqualTo(state.epoch()));
        assertThat(ledger.cases("Refund")).as("nothing was lost or added").hasSize(cases.size());
    }

    // ---- G4 -------------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"automatic", "approver"})
    void everyCommandOfThePreviousReleaseTakesItsArgumentsAndAnswersInTheSameShape(String scenario) throws Exception {
        open(scenario);
        List<String> commands = Files.readAllLines(dir.resolve("commands.txt"));
        int n = 0;
        for (String c : commands) {
            outBytes.reset();
            errBytes.reset();
            String[] a = c.split("\\|");
            int code;
            switch (a[0]) {
                case "status" -> code = AutonomyCommands.status(store, "Refund", null, false, clock, new PrintStream(outBytes, true), new PrintStream(errBytes, true));
                case "status-json" -> code = AutonomyCommands.status(store, null, null, true, clock, new PrintStream(outBytes, true), new PrintStream(errBytes, true));
                case "history" -> code = AutonomyCommands.history(store, null, new PrintStream(outBytes, true), new PrintStream(errBytes, true));
                case "outcome" -> code = AutonomyCommands.outcome(store, "Refund", a[1], a[2], null, null, env());
                case "move" -> code = AutonomyCommands.move(store, "Refund", a[1], a[2], Boolean.parseBoolean(a[3]), a[4].equals("emergency"), a[4], null, null, env());
                case "freeze" -> code = AutonomyCommands.freeze(store, "Refund", true, a[1], env());
                case "unfreeze" -> code = AutonomyCommands.freeze(store, "Refund", false, a[1], env());
                case "approve" -> code = AutonomyCommands.decide(store, "Refund", a[1], true, a[2], null, "risk-owner", env());
                case "reject" -> code = AutonomyCommands.decide(store, "Refund", a[1], false, a[2], null, "risk-owner", env());
                case "replay" -> {
                    ReplayCommand rc = new ReplayCommand();
                    rc.script = script;
                    rc.candidate = script;
                    rc.decision = "Refund";
                    rc.store = store.toFile();
                    rc.seed = 1;
                    rc.repeat = 1;
                    rc.format = "md";
                    code = ReplayCommand.replay(rc, env());
                }
                default -> throw new IllegalStateException(c);
            }
            String base = String.format("%02d-%s", ++n, a[0]);
            Path golden = FIXTURE.resolve(scenario);
            String where = scenario + " " + base + " (" + c + ")";
            assertThat(String.valueOf(code)).as(where + " exit code").isEqualTo(Files.readString(golden.resolve(base + ".code")));
            String before = Files.readString(golden.resolve(base + ".out")).replace("@ROOT@", dir.toString());
            String now = outBytes.toString();
            if (a[0].equals("status-json")) sameJson(where, JSON.readTree(before), JSON.readTree(now), "$");
            else if (a[0].equals("replay")) sameReplay(where, before, now);
            else sameText(where, before, now);
            String errBefore = Files.readString(golden.resolve(base + ".err")).replace("@ROOT@", dir.toString());
            sameText(where + " (stderr)", errBefore, errBytes.toString());
        }
        assertThat(n).isGreaterThan(10);
    }

    /** The same lines, in the same order, apart from the id a replay gives itself. */
    static void sameText(String where, String before, String now) {
        List<String> a = lines(before), b = lines(now);
        assertThat(b).as(where + "\n--- previous release:\n" + before + "--- now:\n" + now).isEqualTo(a);
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) out.add(line.replaceAll("r[0-9a-f]{8,}-[0-9a-f]{1,4}", "r…")
                .replaceAll("operator:[A-Za-z0-9_.\\-]+", "operator:USER")); // the fixtures were written as one user; the operator is whoever runs the test
        return out;
    }

    /** Every key the previous release printed is still there with the same value; the new release may add keys. */
    static void sameJson(String where, JsonNode before, JsonNode now, String path) {
        if (before.isObject()) {
            assertThat(now.isObject()).as(where + " " + path).isTrue();
            before.fields().forEachRemaining(e -> {
                String key = e.getKey();
                assertThat(now.has(key)).as(where + " " + path + "." + key + " is still printed").isTrue();
                sameJson(where, e.getValue(), now.get(key), path + "." + key);
            });
        } else if (before.isArray()) {
            assertThat(now.isArray()).as(where + " " + path).isTrue();
            assertThat(now.size()).as(where + " " + path + " length").isEqualTo(before.size());
            for (int i = 0; i < before.size(); i++) sameJson(where, before.get(i), now.get(i), path + "[" + i + "]");
        } else {
            assertThat(now.asText()).as(where + " " + path).isEqualTo(before.asText());
        }
    }

    /** A replay report has the same sections, in the same order, and the same counts of cases selected and replayed. */
    static void sameReplay(String where, String before, String now) {
        List<String> a = before.lines().filter(l -> l.startsWith("#")).map(l -> l.replaceAll("\\(r[0-9a-f-]+\\)", "(…)")).toList();
        List<String> b = now.lines().filter(l -> l.startsWith("#")).map(l -> l.replaceAll("\\(r[0-9a-f-]+\\)", "(…)")).toList();
        assertThat(b).as(where + " sections").isEqualTo(a);
        assertThat(now.lines().filter(l -> l.contains("cases selected")).toList()).as(where + " counts")
                .isEqualTo(before.lines().filter(l -> l.contains("cases selected")).toList());
    }
}
