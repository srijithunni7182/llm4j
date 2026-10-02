package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The operator layer's journal arithmetic on its own: resolving targets, rewinding, resetting and reading the timeline (spec loom-rewind-and-fork R5, R6). */
class RunTravelUnitTest {

    private static RunJournal run() {
        RunJournal j = RunJournal.inMemory();
        j.put("W/s0", new RunJournal.Entry("delegate", "a"));
        j.put("W/s0#usage:1", new RunJournal.Entry("usage", Map.of("prompt", 10, "completion", 5, "cost", "0.5", "agent", "A")));
        j.put("W/s1#checkpoint", new RunJournal.Entry("checkpoint", Map.of("name", "mid")));
        j.put("W/s1", new RunJournal.Entry("handoff", "b"));
        j.put("W/s2", new RunJournal.Entry("delegate", "c"));
        j.put("W/s2#usage:1", new RunJournal.Entry("usage", Map.of("prompt", 7, "cost", "not a number", "agent", "B")));
        j.put("W/s2#usage:2", new RunJournal.Entry("usage", "odd"));
        j.put("W/s3", new RunJournal.Entry("failed", "boom"));
        j.put("W/s3#usage:1", new RunJournal.Entry("usage", Map.of("cost", 2)));
        j.put("W/s4#checkpoint", new RunJournal.Entry("checkpoint", "not a map"));
        j.put("W/s5/e0", new RunJournal.Entry("delegate", "branch"));
        return j;
    }

    @Test
    @Tag("RW-V5.1")
    void aTargetIsACheckpointTheStartOrAStatementTheRunExecuted() {
        RunJournal j = run();
        assertThat(RunTravel.resolve(j, "W", "start")).isEqualTo(new RunTravel.Target("W/s", 0, "start"));
        assertThat(RunTravel.resolve(j, "W", "mid")).isEqualTo(new RunTravel.Target("W/s", 2, "mid"));
        assertThat(RunTravel.resolve(j, "W", "W/s2")).isEqualTo(new RunTravel.Target("W/s", 2, null));
    }

    @Test
    @Tag("RW-V5.2")
    void anythingElseIsRefusedNamingWhatWouldHaveWorked() {
        RunJournal j = run();
        for (String bad : new String[] {"nope", "W/s9", "W/s5/e0"}) {
            assertThatThrownBy(() -> RunTravel.resolve(j, "W", bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a checkpoint").hasMessageContaining("W/s0, W/s1, W/s2, W/s3, mid, start");
        }
    }

    @Test
    @Tag("RW-V5.3")
    void aRewindIsHeldByEffectsUnlessTheOperatorSaysKeepOrRepeat() {
        RunJournal j = run();
        j.put("W/s2#effect:mail:h", new RunJournal.Entry("effect_done", "x"));
        assertThatThrownBy(() -> RunTravel.rewind(j, "W", "mid", Map.of(), "ask first", false, "r", "ada"))
                .isInstanceOf(RunTravel.Held.class);
        var kept = RunTravel.rewind(j, "W", "mid", Map.of("k", "v"), "keep", true, "because", "ada");
        assertThat(kept.generation()).isEqualTo(2);
        assertThat(kept.name()).isEqualTo("mid");
        assertThat(kept.askAgain()).isTrue();
        var byStatement = RunTravel.rewind(j, "W", "W/s3", Map.of(), "repeat", false, "again", "ada");
        assertThat(byStatement.name()).isEqualTo("W/s3");
        assertThat(byStatement.by()).isEqualTo("ada");
    }

    @Test
    @Tag("RW-V5.8")
    void resetStartsAgainAndClearsAPauseAndFailedOnlyRetriesWhatFailed() {
        RunJournal j = run();
        Map<String, Object> pause = new LinkedHashMap<>();
        pause.put("state", "waiting");
        j.put("#suspension", new RunJournal.Entry("suspension", pause));
        var changed = RunTravel.reset(j, "W", false, "keep", "redo", "ada");
        assertThat(changed).containsExactly("rewound to start (generation 2)", "cleared the recorded pause");
        assertThat(((Map<?, ?>) j.get("#suspension").get().value()).get("state")).isEqualTo("cleared");

        RunJournal odd = run();
        odd.put("#suspension", new RunJournal.Entry("suspension", "just text"));
        assertThat(RunTravel.reset(odd, "W", false, "keep", "redo", "ada")).hasSize(2);
        assertThat(((Map<?, ?>) odd.get("#suspension").get().value()).get("state")).isEqualTo("cleared");

        RunJournal failed = run();
        var retried = RunTravel.reset(failed, "W", true, "ask first", "retry", "ada");
        assertThat(retried).containsExactly("W/s3 (failed: boom)");
        assertThat(failed.get("W/s3").get().kind()).isEqualTo("retry");
        assertThat(failed.get("W/s0").get().kind()).isEqualTo("delegate");
    }

    @Test
    @Tag("RW-V5.9")
    void copyingAJournalCopiesEveryEntryAndLeavesTheSourceAlone() {
        RunJournal from = run();
        RunJournal into = RunJournal.inMemory();
        assertThat(RunTravel.copy(from, into)).isEqualTo(from.all().size());
        assertThat(into.all()).isEqualTo(from.all());
    }

    @Test
    @Tag("RW-V6.1")
    void theTimelineSeparatesWhatWasKeptFromWhatWasReplacedAndToleratesOddEntries() {
        RunJournal j = run();
        RunTravel.rewind(j, "W", "mid", Map.of("why", "x\ny"), "keep", false, "second\nthought", "ada");
        j.put("W/s2~2", new RunJournal.Entry("delegate", "c again"));
        j.put("W/s2~2#usage:1", new RunJournal.Entry("usage", Map.of("prompt", 3, "completion", 1, "cost", "1", "agent", "B")));

        RunTravel.Timeline t = RunTravel.timeline(j);
        assertThat(t.steps()).extracting(RunTravel.Row::id).containsSubsequence("W/s2", "W/s2~2");
        assertThat(t.steps()).filteredOn(r -> r.id().equals("W/s2")).extracting(RunTravel.Row::state).containsExactly("discarded");
        assertThat(t.steps()).filteredOn(r -> r.id().equals("W/s2~2")).extracting(RunTravel.Row::generation).containsExactly(2);
        assertThat(t.steps()).filteredOn(r -> r.id().equals("W/s3")).extracting(RunTravel.Row::state).containsExactly("discarded");
        assertThat(RunTravel.timeline(run()).steps()).filteredOn(r -> r.id().equals("W/s3")).extracting(RunTravel.Row::state).containsExactly("failed");
        assertThat(t.steps()).filteredOn(r -> r.id().equals("W/s1")).extracting(RunTravel.Row::kind).containsExactly("handoff");
        assertThat(t.checkpoints()).anyMatch(c -> c.startsWith("mid (W/s1)"));
        assertThat(t.discardedTokens()).isEqualTo(7);
        assertThat(t.keptTokens()).isEqualTo(15 + 4);
        assertThat(t.text()).contains("Rewinds:", "generation 2", "second?thought", "carrying", "Spend: kept").doesNotContain("second\nthought");

        RunJournal retry = run();
        retry.put("W/s3", new RunJournal.Entry("retry", "boom"));
        assertThat(RunTravel.timeline(retry).steps()).filteredOn(r -> r.id().equals("W/s3")).extracting(RunTravel.Row::state).containsExactly("retry");
        assertThat(RunTravel.timeline(RunJournal.inMemory()).text()).doesNotContain("Rewinds:").doesNotContain("Checkpoints reached");
    }

    @Test
    @Tag("RW-V8.3")
    void neutralisingKeepsOrdinaryTextAndHidesEverythingThatCouldForgeALine() {
        assertThat(RunTravel.neutralise(null)).isEmpty();
        assertThat(RunTravel.neutralise("ok é 日本")).isEqualTo("ok é 日本");
        assertThat(RunTravel.neutralise("a\u007fb\u0090c d\u0000")).isEqualTo("a?b?c?d?");
    }
}
