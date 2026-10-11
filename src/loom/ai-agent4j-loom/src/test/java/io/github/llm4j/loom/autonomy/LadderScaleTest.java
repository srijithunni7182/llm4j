package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A decision with a long record: what one case costs when the ledger holds a hundred thousand. */
@Tag("L5")
class LadderScaleTest {

    static final String SCRIPT = """
            agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest { mode: confirm climb when history.count >= 100 and history.rate(agrees).lower95 >= 0.9 slip when history.last(50).rate(agrees) < 0.3 }
                rung act { mode: autonomous climb when history.count >= 100000000 slip when history.last(50).rate(agrees) < 0.3 sample 5% blind }
                climbing is automatic
            }
            decision D { proposed by: T choices: a, b, escalate group cases by: tier ask: p ladder: L ceiling: suggest when the agent changes: start over }
            """;

    @Test
    void oneCaseCostsLittleWithAHundredThousandBehindIt() {
        LoomScript s = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        MemoryLedger ledger = new MemoryLedger();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        LadderEngine engine = new LadderEngine(LadderPolicy.of(s, s.getDecisions().get(0)), ledger, clock);
        int scopes = 50, cases = 100_000;
        for (int sc = 0; sc < scopes; sc++) engine.standing("scope" + sc, "id-a", "m");
        for (int i = 0; i < cases; i++) {
            String id = "c" + i;
            String scope = "scope" + (i % scopes);
            Instant t = clock.instant().plusSeconds(i);
            ledger.append(new Rec(id + "#case#1", "D", Rec.CASE, t, id, 1, Rec.map("scope", scope, "level", "watch", "identity", "id-a", "epoch", 1, "fields", Map.of("amount", String.valueOf(i % 500)))));
            ledger.append(new Rec(id + "#proposed#1", "D", Rec.PROPOSED, t, id, 1, Rec.map("choice", "a")));
            ledger.append(new Rec(id + "#responded#1", "D", Rec.RESPONDED, t.plusSeconds(1), id, 1, Rec.map("kind", "accept", "choice", i % 10 == 0 ? "b" : "a", "by", "p")));
        }
        clock.set(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(cases + 10));
        long first = 0, rest = 0;
        for (int k = 0; k < 6; k++) {
            long start = System.nanoTime();
            engine.afterResponse("scope" + k);
            long took = (System.nanoTime() - start) / 1_000_000;
            if (k == 0) first = took;
            else rest += took;
        }
        long perCase = rest / 5;
        System.out.println("LADDER-SCALE with " + cases + " cases: first step " + first + " ms (folds the record), later steps " + perCase + " ms each");
        assertThat(first).as("milliseconds for the first ladder step, which folds the whole record").isLessThan(3000);
        assertThat(perCase).as("milliseconds for one case's ladder step").isLessThan(1000);
    }
}
