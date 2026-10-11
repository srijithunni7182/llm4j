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

    LadderHarness(String source) {
        this.script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        this.decision = script.getDecisions().get(0);
        this.policy = LadderPolicy.of(script, decision);
        this.engine = new LadderEngine(policy, ledger, clock);
    }

    /** A new engine over the same ledger and clock, as after a restart (or with the script edited, when given another source). */
    LadderEngine restart(String source) {
        LoomScript s = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return new LadderEngine(LadderPolicy.of(s, s.getDecisions().get(0)), ledger, clock);
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
