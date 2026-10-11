package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.MemoryLedger;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** A ladder over a memory ledger and a clock the test moves, with a way to put whole cases in the ledger in one call. */
final class LadderHarness {

    static final Instant START = Instant.parse("2026-10-01T09:00:00Z");

    final MemoryLedger ledger = new MemoryLedger();
    final MutableClock clock = new MutableClock(START);
    final LoomScript script;
    final DecisionDef decision;
    final LadderPolicy policy;
    final LadderEngine engine;
    private int counter;

    /** A harness over a reference ladder in {@code src/test/resources/ladders}, with its imports resolved the way {@code weave} resolves them. */
    static LadderHarness fromResource(String name, java.nio.file.Path dir) throws java.io.IOException {
        LadderHarness h = new LadderHarness(load(name, dir, null, null));
        h.resource = name;
        h.dir = dir;
        return h;
    }

    private static LoomScript load(String name, java.nio.file.Path dir, String from, String to) throws java.io.IOException {
        java.nio.file.Path file = dir.resolve(from == null ? name : "edited-" + name);
        try (var in = LadderHarness.class.getResourceAsStream("/ladders/" + name)) {
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (from != null) {
                if (!text.contains(from)) throw new IllegalArgumentException("the ladder has no \"" + from + "\"");
                text = text.replace(from, to);
            }
            java.nio.file.Files.writeString(file, text);
        }
        return new io.github.llm4j.loom.execution.LoomLoader().load(file.toString());
    }

    private String resource;
    private java.nio.file.Path dir;

    /** The reference ladder with one phrase changed, loaded the way {@code weave} loads it: the script after someone edits it. */
    LoomScript edited(String from, String to) throws java.io.IOException {
        return load(resource, dir, from, to);
    }

    LadderHarness(String source) {
        this(new LoomParser(new Lexer(source).tokenize()).parseScript());
    }

    LadderHarness(LoomScript script) {
        this.script = script;
        this.decision = script.getDecisions().get(0);
        this.policy = LadderPolicy.of(script, decision);
        this.engine = new LadderEngine(policy, ledger, clock);
    }

    /** A new engine over the same ledger and clock, as after a restart (or with the script edited, when given another source). */
    LadderEngine restart(String source) {
        LoomScript s = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return new LadderEngine(LadderPolicy.of(s, s.getDecisions().get(0)), ledger, clock);
    }

    /** As {@link #restart(String)}, for a script already loaded. */
    LadderEngine restart(LoomScript s) {
        return new LadderEngine(LadderPolicy.of(s, s.getDecisions().get(0)), ledger, clock);
    }

    /** One answered artifact case: the agent proposed {@code proposed}; the person {@code kind} (accept, edit, reject) it, ending with {@code fin}. */
    String artifact(String scope, String proposed, String kind, String fin, String level, String identity, int epoch, Map<String, String> fields, long reviewMillis) {
        String id = "case-" + (++counter);
        clock.advance(Duration.ofHours(1));
        Instant t = clock.instant();
        ledger.append(new Rec(id + "#case#1", decision.getName(), Rec.CASE, t, id, 1,
                Rec.map("scope", scope, "level", level, "how", level, "fields", fields, "identity", identity, "epoch", epoch, "servedModel", "m-1")));
        ledger.append(new Rec(id + "#proposed#1", decision.getName(), Rec.PROPOSED, t, id, 1, Rec.map("value", proposed, "reasoning", "r")));
        boolean agent = level.equals("publish") && reviewMillis == 0;
        ledger.append(new Rec(id + "#responded#1", decision.getName(), Rec.RESPONDED, t.plusMillis(Math.max(1, reviewMillis)), id, 1,
                Rec.map("kind", kind, "final", fin, "by", agent ? "agent" : "editor", "shown", !level.equals("publish"), "millis", reviewMillis)));
        return id;
    }

    /** Puts one answered case in the ledger, as the runtime would write it, one hour after the last. */
    String answered(String scope, String proposed, String decided, String level, String identity, int epoch, Map<String, String> fields) {
        String id = "case-" + (++counter);
        clock.advance(Duration.ofHours(1));
        Instant t = clock.instant();
        ledger.append(new Rec(id + "#case#1", decision.getName(), Rec.CASE, t, id, 1,
                Rec.map("scope", scope, "level", level, "how", level, "fields", fields, "identity", identity, "epoch", epoch, "servedModel", "m-1")));
        ledger.append(new Rec(id + "#proposed#1", decision.getName(), Rec.PROPOSED, t, id, 1, Rec.map("choice", proposed, "reasoning", "r")));
        boolean agent = level.equals("act");
        ledger.append(new Rec(id + "#responded#1", decision.getName(), Rec.RESPONDED, t.plusSeconds(30), id, 1,
                Rec.map("kind", agent || proposed.equals(decided) ? "accept" : "reject", "choice", decided, "by", agent ? "agent" : "lead",
                        "shown", level.equals("suggest"), "millis", 30000L)));
        return id;
    }

    String answered(String scope, String proposed, String decided) {
        return answered(scope, proposed, decided, "watch", "id-a", 1, Map.of());
    }

    void answeredMany(int n, String scope, String proposed, String decided) {
        for (int i = 0; i < n; i++) answered(scope, proposed, decided);
    }
}
