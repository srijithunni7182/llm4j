package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A ladder behaves the same whether its ledger is in memory, in files or in a database. */
@Tag("L3")
class LadderStoresTest {

    static final String SCRIPT = """
            agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest { mode: confirm climb when history.count >= 4 and history.rate(agrees) >= 0.8 slip when history.last(4).rate(agrees) < 0.3 }
                climbing is automatic
            }
            decision D { proposed by: T choices: a, b, escalate ask: p ladder: L ceiling: suggest when the agent changes: start over }
            """;

    @TempDir
    Path dir;

    private static LadderPolicy policy(String source) {
        LoomScript s = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return LadderPolicy.of(s, s.getDecisions().get(0));
    }

    private int n;

    private void answered(Ledger ledger, MutableClock clock, String proposed, String decided) {
        String id = "c-" + (++n);
        clock.advance(Duration.ofHours(1));
        Instant t = clock.instant();
        ledger.append(new Rec(id + "#case#1", "D", Rec.CASE, t, id, 1, Rec.map("scope", "all", "level", "watch", "identity", "id-a", "epoch", 1, "fields", Map.of())));
        ledger.append(new Rec(id + "#proposed#1", "D", Rec.PROPOSED, t, id, 1, Rec.map("choice", proposed)));
        ledger.append(new Rec(id + "#responded#1", "D", Rec.RESPONDED, t.plusSeconds(1), id, 1, Rec.map("kind", "accept", "choice", decided, "by", "p")));
    }

    @Test
    void theSameLifeOnEveryStore() {
        Stores.each(dir, stores -> {
            n = 0;
            MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
            LadderEngine engine = new LadderEngine(policy(SCRIPT), stores.ledger, clock);
            assertThat(engine.standing("all", "id-a", "m").rungName()).as(stores.kind).isEqualTo("watch");
            for (int i = 0; i < 4; i++) answered(stores.ledger, clock, "a", "a");
            assertThat(engine.afterResponse("all")).as(stores.kind).extracting(LadderEngine.Change::kind).containsExactly("promoted");

            // a restart: a new engine over the same store finds the same standing
            LadderEngine again = new LadderEngine(policy(SCRIPT), stores.ledger, clock);
            LevelLog.Standing s = again.standing("all", "id-a", "m");
            assertThat(s.rungName()).as(stores.kind).isEqualTo("suggest");
            assertThat(s.version()).as(stores.kind).isEqualTo(2);
            assertThat(s.reason()).contains("history.count >= 4");

            // a slip, a freeze and an outcome all survive
            for (int i = 0; i < 4; i++) answered(stores.ledger, clock, "a", "b");
            assertThat(again.afterResponse("all")).as(stores.kind).extracting(LadderEngine.Change::kind).containsExactly("demoted");
            again.freeze("drill", "ops");
            assertThat(new LadderEngine(policy(SCRIPT), stores.ledger, clock).frozen()).as(stores.kind).isTrue();
            again.unfreeze("done", "ops");
            assertThat(new LadderEngine(policy(SCRIPT), stores.ledger, clock).frozen()).as(stores.kind).isFalse();
            assertThat(again.outcome("c-1", "views", 12, "note")).as(stores.kind).isEmpty();
            assertThat(again.cases().get(0).outcomes()).extracting(c -> c.name()).containsExactly("views");
            assertThat(again.cases().get(0).outcomes().get(0).value()).isIn(12, 12.0, 12L);
        });
    }

    @Test
    void writersRacingForTheSameStepLeaveOneRecordOnEveryStore() {
        Stores.each(dir, stores -> {
            n = 0;
            MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
            LadderEngine engine = new LadderEngine(policy(SCRIPT), stores.ledger, clock);
            engine.standing("all", "id-a", "m");
            for (int i = 0; i < 4; i++) answered(stores.ledger, clock, "a", "a");
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<List<LadderEngine.Change>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return new LadderEngine(policy(SCRIPT), stores.ledger, clock).afterResponse("all");
                }));
            }
            go.countDown();
            int made = 0;
            try {
                for (Future<List<LadderEngine.Change>> f : results) made += f.get().size();
            } catch (Exception e) {
                throw new AssertionError(stores.kind, e);
            }
            pool.shutdown();
            assertThat(made).as(stores.kind).isEqualTo(1);
            assertThat(engine.log().current("all").orElseThrow().version()).as(stores.kind).isEqualTo(2);
        });
    }
}
