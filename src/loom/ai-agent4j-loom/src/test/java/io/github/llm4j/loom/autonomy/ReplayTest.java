package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replaying past cases under a candidate: a fork of each case, graded against what people decided (spec loom-earned-autonomy R7). */
class ReplayTest {

    @TempDir
    Path dir;

    private ReplayHarness replayHarness(int cases) throws Exception {
        DecideHarness live = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.withUpstream(""));
        ReplayHarness h = new ReplayHarness(live, dir);
        h.seedCases(cases);
        return h;
    }

    /** The candidate behaves as the incumbent did. */
    private static void sameAsIncumbent(ReplayHarness h) {
        h.candidateAgent = f -> {
            int amount = Integer.parseInt(f.get("amount"));
            return new String[] {amount < 300 ? "approve" : "reject", "incumbent reasoning", "0.9"};
        };
    }

    @Test
    @Tag("EA-V7.1")
    @Tag("EA-V7.4")
    void aCandidateThatIsTheIncumbentReproducesItsChoiceOnEveryCaseAndPaysForNothingUpstream() throws Exception {
        ReplayHarness h = replayHarness(16);
        sameAsIncumbent(h);
        int upstreamBefore = h.live.modelCalls;

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        assertThat(report.selected).isEqualTo(16);
        assertThat(report.replayed).isEqualTo(16);
        assertThat(report.flips).isEmpty();
        assertThat(report.candidate.rate()).isEqualTo(report.incumbent.rate());
        assertThat(report.candidate.lowerBound()).isEqualTo(report.incumbent.lowerBound());
        assertThat(report.replayableShare).isEqualTo(1.0);
        assertThat(h.candidateCalls).as("one model call per replayed case: the upstream steps were read from the journal").isEqualTo(16);
        assertThat(h.summarizerCalled).as("the summary was not made again").isFalse();
        assertThat(h.live.modelCalls).as("nothing ran against the live agent").isEqualTo(upstreamBefore);
    }

    @Test
    @Tag("EA-V7.2")
    void aDifferentCandidateListsExactlyTheFlippedCasesWithBothChoicesTheHumansAndBothReasoningsUnsafeFirst() throws Exception {
        ReplayHarness h = replayHarness(16);
        // the candidate approves everything: it differs on the cases the incumbent rejected (amount >= 300) and where people rejected (100..299 the incumbent approved)
        h.candidateAgent = f -> new String[] {"approve", "approve all of them", "0.8"};

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        long expected = java.util.stream.IntStream.range(0, 16).map(i -> 20 + i * 20).filter(a -> a >= 300).count();
        assertThat(report.flips).hasSize((int) expected);
        assertThat(report.flips).allSatisfy(f -> {
            assertThat(f.incumbent()).isEqualTo("reject");
            assertThat(f.candidate()).isEqualTo("approve");
            assertThat(f.human()).isEqualTo("reject");
            assertThat(f.unsafe()).as("approve proposed where a person rejected is the declared dangerous mistake").isTrue();
            assertThat(f.reasoning()).isEqualTo("approve all of them");
            assertThat(f.fields()).containsKey("amount");
        });
        assertThat(report.candidate.dangerousRate()).isGreaterThan(report.incumbent.dangerousRate());
        String md = report.markdown();
        assertThat(md).contains("**UNSAFE**").contains("candidate's reasoning: approve all of them");
        assertThat(md.indexOf("## Headline")).isLessThan(md.indexOf("## Flips"));
        assertThat(md.indexOf("## What was replayed")).isLessThan(md.indexOf("## Tools in replay"));
        assertThat(md.indexOf("## The level the candidate would earn")).isLessThan(md.indexOf("## Flips"));
        assertThat(md.indexOf("## Flips")).isLessThan(md.indexOf("## By scope"));
    }

    private static String snapshot(ReplayHarness h) {
        StringBuilder b = new StringBuilder();
        b.append(h.live.ledger.records("Refund")).append(h.live.levels.scopes("Refund")).append(h.live.levels.freeze("Refund"));
        h.live.journals.forEach((id, j) -> b.append(id).append(new java.util.TreeMap<>(j.all())));
        return b.toString();
    }

    @Test
    @Tag("EA-V7.3")
    void nothingChangesAReplayWritesOnlyItsOwnDirectoryAndAsksNoOne() throws Exception {
        ReplayHarness h = replayHarness(10);
        h.candidateAgent = f -> new String[] {"escalate", "unsure", "0.1"};
        String before = snapshot(h);
        int asked = h.live.asked.size();
        Path storeBefore = h.store;
        List<String> filesBefore = listing(dir.resolve("store"));

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        assertThat(report.replayed).isEqualTo(10);
        assertThat(snapshot(h)).as("ledger, levels and every run's journal are unchanged").isEqualTo(before);
        assertThat(h.live.asked).as("no person was asked").hasSize(asked);
        assertThat(h.candidateQuestions).isEmpty();
        List<String> filesAfter = listing(dir.resolve("store"));
        assertThat(filesAfter.stream().filter(f -> !filesBefore.contains(f)).toList()).allMatch(f -> f.startsWith("replays/"));
        assertThat(filesAfter.stream().filter(f -> f.endsWith("plan.json") || f.endsWith("log.jsonl") || f.endsWith("report.md") || f.endsWith("report.json"))).hasSize(4);
        assertThat(storeBefore).isEqualTo(h.store);
    }

    static List<String> listing(Path root) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
        }
    }

    @Test
    @Tag("EA-V7.9")
    void sameLedgerCandidateAndSeedGiveTheSameSelectionAndOrderADifferentSeedADifferentSample() throws Exception {
        ReplayHarness h = replayHarness(30);
        sameAsIncumbent(h);
        ReplayOptions a = new ReplayOptions(h.incumbent(), null, null, 10, 7, 1, false, false, false, 0, null, null);

        List<String> first = ReplayEngine.select(h.live.ledger.cases("Refund"), a, null).stream().map(Case::id).toList();
        List<String> second = ReplayEngine.select(h.live.ledger.cases("Refund"), a, null).stream().map(Case::id).toList();
        List<String> other = ReplayEngine.select(h.live.ledger.cases("Refund"), new ReplayOptions(h.incumbent(), null, null, 10, 8, 1, false, false, false, 0, null, null), null).stream().map(Case::id).toList();

        assertThat(first).hasSize(10).isEqualTo(second);
        assertThat(other).isNotEqualTo(first);
        ReplayReport r1 = h.engine.run("Refund", a, null);
        ReplayReport r2 = h.engine.run("Refund", a, null);
        assertThat(r1.replayed).isEqualTo(10);
        assertThat(r1.json().replace(r1.id, "ID")).isEqualTo(r2.json().replace(r2.id, "ID"));
    }

    @Test
    @Tag("EA-V7.9")
    void repeatShowsHowOftenTheCandidatesChoiceChangesAcrossRunsOfTheSameCase() throws Exception {
        ReplayHarness h = replayHarness(8);
        int[] n = {0};
        h.candidateAgent = f -> new String[] {n[0]++ % 2 == 0 ? "approve" : "reject", "coin", "0.5"};

        ReplayReport report = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 2, false, false, false, 0, null, null), null);

        assertThat(report.repeat).isEqualTo(2);
        assertThat(report.stability).isZero();
        assertThat(report.markdown()).contains("## Stability").contains("0.0% of cases got the same choice");
    }

    @Test
    @Tag("EA-V7.7")
    void aCaseWhoseJournalIsGoneOrWhoseProposalWasMarkedIsSkippedWithAReasonAndTheReportSaysHowMuchWasReplayed() throws Exception {
        ReplayHarness h = replayHarness(10);
        sameAsIncumbent(h);
        h.gone.add("run-8");
        h.gone.add("run-9");
        // mark two cases the way a proposal that went wrong is marked
        h.live.ledger.append(new Rec("run-5/Triage/s1#proposed#9", "Refund", Rec.PROPOSED, DecideHarness.T0, "run-5/Triage/s1", 1, Rec.map("choice", "approve", "flags", List.of("evidence_truncated"))));
        h.live.ledger.append(new Rec("run-6/Triage/s1#proposed#9", "Refund", Rec.PROPOSED, DecideHarness.T0, "run-6/Triage/s1", 1, Rec.map("choice", "approve", "flags", List.of("effects_during_proposal"))));
        h.live.ledger.purgeFields("Refund", DecideHarness.T0.plusSeconds(3600L * 4)); // the first cases' fields have expired

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        assertThat(report.skipped).containsEntry("journal_missing", 2).containsEntry("evidence_truncated", 1).containsEntry("effects_during_proposal", 1);
        assertThat(report.skipped.get("fields_masked")).isNotNull();
        assertThat(report.replayed + report.skipped.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
        assertThat(report.replayableShare).isLessThan(1.0).isEqualTo((double) report.replayed / report.selected);
        assertThat(report.markdown()).contains("Not replayable:").contains("journal_missing: 2");
    }

    @Test
    @Tag("EA-V7.8")
    void aCandidateWhoseScriptDiffersBeforeTheDecideIsRefusedPerCaseUnlessDriftIsAllowedAndOneThatOnlyChangesTheAgentHasNone() throws Exception {
        ReplayHarness h = replayHarness(6);
        sameAsIncumbent(h);
        Path drifting = h.script("drift.loom", h.live.script.replace("Summarise {ticket}", "Summarise carefully {ticket}"));
        Path agentOnly = h.script("agent.loom", h.live.script.replace("You are Triager.", "You are a stricter Triager."));

        ReplayReport refused = h.engine.run("Refund", h.options(drifting), null);
        assertThat(refused.replayed).isZero();
        assertThat(refused.skipped).containsEntry("prefix_drift", 6);
        assertThat(refused.skips.get(0).detail()).contains("statement 1").contains("Summarise");

        ReplayReport allowed = h.engine.run("Refund", new ReplayOptions(drifting, null, null, 500, 1, 1, false, true, false, 0, null, null), null);
        assertThat(allowed.replayed).isEqualTo(6);

        ReplayReport changedAgent = h.engine.run("Refund", h.options(agentOnly), null);
        assertThat(changedAgent.replayed).isEqualTo(6);
        assertThat(changedAgent.skipped).isEmpty();
    }

    @Test
    @Tag("EA-V7.13")
    void aCandidateThatHasNoSuchDecisionOrDoesNotLoadIsAnErrorNamingWhy() throws Exception {
        ReplayHarness h = replayHarness(3);
        Path other = h.script("other.loom", h.live.script.replace("decision Refund", "decision Other").replace("decide Refund", "decide Other"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.engine.run("Refund", h.options(other), null)).hasMessageContaining("has no decision named Refund");
        Path broken = h.script("broken.loom", "decision Refund { frobnicate }");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.engine.run("Refund", h.options(broken), null)).isInstanceOf(Exception.class);
        Path none = h.script("empty.loom", h.live.script);
        ReplayReport empty = new ReplayEngine(new MemoryLedger(), dir.resolve("e"), locator -> java.util.Optional.empty(), (l, s, b, r, j) -> null, h.clock).run("Refund", h.options(none), null);
        assertThat(empty.selected).isZero();
        assertThat(empty.markdown()).contains("0 cases selected");
    }

    @Test
    @Tag("EA-V7.12")
    void theReportHasEverySectionInOrderAndTheMarkdownAndJsonAgree() throws Exception {
        ReplayHarness h = replayHarness(12);
        h.candidateAgent = f -> new String[] {"reject", "always no", "0.4"};
        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        String md = report.markdown();
        List<String> sections = List.of("## What was replayed", "## Tools in replay", "## Headline", "## The level the candidate would earn", "## Flips", "## By scope");
        int at = -1;
        for (String s : sections) {
            assertThat(md.indexOf(s)).as(s).isGreaterThan(at);
            at = md.indexOf(s);
        }
        Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(report.json(), Map.class);
        assertThat(json.get("replayed")).isEqualTo(report.replayed);
        assertThat(((List<?>) json.get("flips"))).hasSize(report.flips.size());
        assertThat(json.get("levelEarned")).isEqualTo(report.levelEarned);
        assertThat(((Map<?, ?>) json.get("scopes")).keySet().stream().map(String::valueOf).toList()).containsExactlyInAnyOrder("gold", "basic");
        assertThat(Files.readString(h.store.resolve("replays").resolve(report.id).resolve("report.md"))).isEqualTo(md);
    }
}
