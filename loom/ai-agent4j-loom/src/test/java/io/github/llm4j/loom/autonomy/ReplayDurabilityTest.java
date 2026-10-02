package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Budgets, resuming, and replacing a policy file (spec loom-earned-autonomy R7.9, R7.10). */
class ReplayDurabilityTest {

    @TempDir
    Path dir;

    private ReplayHarness harness(int cases) throws Exception {
        DecideHarness live = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.withUpstream(""));
        ReplayHarness h = new ReplayHarness(live, dir);
        h.seedCases(cases);
        h.candidateAgent = f -> new String[] {Integer.parseInt(f.get("amount")) < 300 ? "approve" : "reject", "ok", "0.9"};
        return h;
    }

    private static String normalised(ReplayReport r) {
        return r.json().replace(r.id, "ID");
    }

    @Test
    @Tag("EA-V7.10")
    void aTokenBudgetStopsCleanlyWithAPartialReportAndResumeFinishesWithTheSameReportAsAnUninterruptedRun() throws Exception {
        ReplayHarness h = harness(12);
        ReplayReport whole = h.engine.run("Refund", h.options(h.incumbent()), null);
        assertThat(whole.partial).isFalse();
        assertThat(whole.replayed).isEqualTo(12);
        assertThat(whole.tokens).as("spend is measured from what the replay itself wrote").isEqualTo(12 * 15);

        ReplayReport cut = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 1, false, false, false, 40, null, null), null);
        assertThat(cut.partial).isTrue();
        assertThat(cut.stoppedBecause).contains("--max-tokens 40");
        assertThat(cut.replayed).isEqualTo(3);
        assertThat(cut.markdown()).contains("**Partial:**");
        int calls = h.candidateCalls;

        ReplayReport finished = h.engine.resume("Refund", cut.id, null, 0);

        assertThat(finished.partial).isFalse();
        assertThat(finished.replayed).isEqualTo(12);
        assertThat(h.candidateCalls - calls).as("the cases already in the log are not run again").isEqualTo(9);
        assertThat(normalised(finished)).isEqualTo(normalised(whole));
        assertThat(Files.readAllLines(h.store.resolve("replays").resolve(cut.id).resolve("log.jsonl")).stream().filter(l -> l.contains("\"row\""))).hasSize(12);
    }

    @Test
    @Tag("EA-V7.10")
    void aCostBudgetStopsAReplayToo() throws Exception {
        ReplayHarness h = harness(8);
        h.priced = true;
        ReplayReport cut = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 1, false, false, false, 0, new BigDecimal("0.0000001"), null), null);
        assertThat(cut.partial).isTrue();
        assertThat(cut.stoppedBecause).contains("--max-cost");
        assertThat(cut.replayed).isBetween(1, 7);
        assertThat(cut.cost.signum()).isPositive();
    }

    @Test
    @Tag("EA-V7.10")
    void resumingAnUnknownReplayIsAnError() throws Exception {
        ReplayHarness h = harness(2);
        assertThatThrownBy(() -> h.engine.resume("Refund", "r-nope", null, 0)).hasMessageContaining("there is no replay r-nope");
        assertThatThrownBy(() -> h.engine.resume("Refund", "../../x", null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("EA-V7.11")
    void aPolicyReplayReplacesTheFileTheAgentReadsForTheCandidateOnlyAndRecordsBothHashes() throws Exception {
        Files.writeString(dir.resolve("refund-policy.md"), "Refunds are allowed within 30 days.");
        DecideHarness live = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(),
                Scripts2.withUpstream("").replace("system: \"You are Triager.\"", "system: \"You are Triager.\" skills: [\"fs://refund-policy.md\"]"));
        ReplayHarness h = new ReplayHarness(live, dir);
        h.seedCases(4);
        h.candidateAgent = f -> new String[] {"approve", "ok", "0.9"};
        Path replacement = dir.resolve("replacement").resolve("refund-policy.md");
        Files.createDirectories(replacement.getParent());
        Files.writeString(replacement, "Refunds are allowed within 90 days. NEW-POLICY-MARKER");

        ReplayReport report = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 1, false, false, false, 0, null, replacement), null);

        assertThat(report.replayed).isEqualTo(4);
        assertThat(h.candidateSystemPrompts).allMatch(p -> p.contains("NEW-POLICY-MARKER"));
        assertThat(report.policyHashes).containsKeys("refund-policy.md (before)", "refund-policy.md (replacement)");
        assertThat(report.policyHashes.get("refund-policy.md (before)")).isNotEqualTo(report.policyHashes.get("refund-policy.md (replacement)"));
        assertThat(Files.readString(dir.resolve("refund-policy.md"))).as("the original is untouched").isEqualTo("Refunds are allowed within 30 days.");
        assertThat(report.markdown()).contains("## Policy replaced");

        Path unrelated = dir.resolve("other.md");
        Files.writeString(unrelated, "x");
        assertThatThrownBy(() -> h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 1, false, false, false, 0, null, unrelated), null))
                .hasMessageContaining("reads no file named other.md").hasMessageContaining("refund-policy.md");
    }

    @Test
    @Tag("EA-V6.3")
    @Tag("EA-V7.16")
    void whenTheAgentChangesTestingOnPastCasesGivesTheNewAgentTheLevelItsReplayEarnsNeverMoreThanBefore() throws Exception {
        // the old agent earned suggest; the new one is as good, so it inherits suggest; a worse one gets less
        for (String quality : new String[] {"same", "worse", "toofew"}) {
            Path work = Files.createDirectories(dir.resolve(quality));
            DecideHarness live = new DecideHarness(work, new MemoryLedger(), new MemoryLevelStore(),
                    Scripts2.withUpstream(""));
            live.script = live.script.replace("trust {", "when the agent changes: test it on past cases\n trust {");
            ReplayHarness h = new ReplayHarness(live, work);
            h.seedCases(quality.equals("toofew") ? 4 : 12);
            h.candidateAgent = quality.equals("worse")
                    ? f -> new String[] {"escalate", "unsure", "0.1"}
                    : f -> new String[] {Integer.parseInt(f.get("amount")) < 100 ? "approve" : "reject", "good", "0.9"};
            Path newScript = h.script("new-agent.loom", live.script.replace("You are Triager.", "You are the new Triager."));

            LevelState old = live.levels.get("Refund", "gold").orElseThrow();
            EpochReplay inherit = new EpochReplay(h.engine, newScript);
            io.github.llm4j.loom.execution.HarnessExecutor.Inherited got = inherit.inherited(
                    DecisionParseTest.parse(live.script).getDecisions().get(0), "gold", old.withLevel(Level.SUGGEST, false, DecideHarness.T0, "earned"), "newidentity");

            switch (quality) {
                case "same" -> {
                    assertThat(got.level()).isEqualTo(Level.SUGGEST);
                    assertThat(got.reason()).contains("replay").contains("r");
                }
                case "worse" -> assertThat(got.level()).isEqualTo(Level.WATCH);
                default -> {
                    assertThat(got.level()).isEqualTo(Level.WATCH);
                    assertThat(got.reason()).contains("too few");
                }
            }
        }
    }
}
