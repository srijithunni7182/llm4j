package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Thousands of replays cost small overlays and the candidate's calls, not copies of the journals (spec loom-earned-autonomy R7.8). */
class ReplayScaleTest {

    @TempDir
    Path dir;

    @Test
    @Tag("EA-V7.14")
    void fiveThousandReplaysOfCasesFromFiftyRunsFinishQuicklyWithBoundedMemory() throws Exception {
        String script = Scripts2.refund("").replace("workflow Triage(ticket, tier, amount, reason, customer_since) {\n    decide Refund -> verdict",
                "workflow Triage(ticket, tier, amount, reason, customer_since) {\n    loop until (verdict == \"never\") max 100 {\n    decide Refund -> verdict\n    }");
        DecideHarness live = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), script);
        // a level the agent has earned would stop asking people; keep it at watch so every case is blind evidence
        live.script = live.script.replace("never go above act", "never go above watch");
        ReplayHarness h = new ReplayHarness(live, dir);
        h.candidateAgent = f -> new String[] {"approve", "ok", "0.9"};
        live.person = f -> "approve";
        for (int run = 0; run < 50; run++) live.runCase("run-" + run, live.inputs(run % 2 == 0 ? "gold" : "basic", 20 + run));
        assertThat(live.ledger.cases("Refund")).hasSize(5000);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long usedBefore = rt.totalMemory() - rt.freeMemory();
        long start = System.nanoTime();
        ReplayReport report = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 5000, 1, 1, false, false, false, 0, null, null), null);
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        System.gc();
        long grown = (rt.totalMemory() - rt.freeMemory()) - usedBefore;

        assertThat(report.selected).isEqualTo(5000);
        assertThat(report.replayed).isEqualTo(5000);
        assertThat(report.flips).isEmpty();
        assertThat(seconds).as("seconds for 5000 replays").isLessThan(30);
        assertThat(grown).as("memory kept after the replay").isLessThan(300L * 1024 * 1024);
    }
}
