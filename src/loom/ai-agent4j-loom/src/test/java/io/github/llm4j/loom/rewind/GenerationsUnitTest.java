package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.EffectScan;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The boundary list on its own: reading, locating steps, identity and the scan of what a rewind would cross (spec loom-rewind-and-fork R2, R4). */
class GenerationsUnitTest {

    private static Generations.Boundary start(Generations g, String block, int from, String effects, boolean askAgain) {
        return g.start(block, from, "cp", "script", "Main/s5", "why", Map.of("k", "v"), effects, askAgain);
    }

    @Test
    @Tag("RW-V2.10")
    void aJournalWrittenByAnotherFormatOrWithAnUnreadableListIsRefused() {
        RunJournal bad = RunJournal.inMemory();
        bad.put(Generations.KEY, new RunJournal.Entry("boundaries", "not a map"));
        assertThatThrownBy(() -> new Generations(bad)).hasMessageContaining("not a boundary list");
        RunJournal other = RunJournal.inMemory();
        other.put(Generations.KEY, new RunJournal.Entry("boundaries", Map.of("version", 2, "boundaries", List.of())));
        assertThatThrownBy(() -> new Generations(other)).hasMessageContaining("different version");
        RunJournal none = RunJournal.inMemory();
        none.put(Generations.KEY, new RunJournal.Entry("boundaries", Map.of("boundaries", List.of())));
        assertThatThrownBy(() -> new Generations(none)).hasMessageContaining("journal format null");
    }

    @Test
    @Tag("RW-V2.1")
    void boundariesRoundTripThroughTheJournalAndAnswerWhereAndWhen() {
        RunJournal journal = RunJournal.inMemory();
        Generations g = new Generations(journal);
        assertThat(g.any()).isFalse();
        assertThat(g.current("Main/s", 3)).isEqualTo(1);
        assertThat(g.startingAt("Main/s", 2)).isNull();
        start(g, "Main/s", 2, "keep", false);
        start(g, "Main/s", 2, "repeat", true);
        start(g, "Main/s", 1, "keep", false);
        start(g, "Other/s", 0, "keep", false);

        Generations again = new Generations(journal);
        assertThat(again.total()).isEqualTo(4);
        assertThat(again.all()).hasSize(4);
        assertThat(again.current("Main/s", 0)).isEqualTo(1);
        assertThat(again.current("Main/s", 1)).isEqualTo(4);
        assertThat(again.current("Main/s", 2)).isEqualTo(4);
        assertThat(again.next("Main/s")).isEqualTo(5);
        assertThat(again.startingAt("Main/s", 2).generation()).isEqualTo(3);
        assertThat(again.startingAt("Main/s", 2).askAgain()).isTrue();
        assertThat(again.rewindsBy("Main/s5")).isEqualTo(4);
        assertThat(again.rewindsBy("nothing")).isZero();
        assertThat(again.all().get(0).carried()).containsEntry("k", "v");
        assertThat(again.all().get(0).fromStep()).isEqualTo("Main/s2~2");
        assertThat(again.all().get(1).fromStep()).isEqualTo("Main/s2~3");
    }

    @Test
    @Tag("RW-V4.1")
    void aStepKeepsItsIdentityUnlessItsGenerationWasStartedToRepeatEffects() {
        Generations g = new Generations(RunJournal.inMemory());
        assertThat(g.identity("Main/s3~2")).isEqualTo("Main/s3");
        start(g, "Main/s", 3, "keep", false);          // generation 2, keeps
        start(g, "Main/s", 3, "repeat", false);        // generation 3, repeats
        assertThat(g.identity("Main/s3~2")).isEqualTo("Main/s3");
        assertThat(g.identity("Main/s3~3")).isEqualTo("Main/s3~3");
        assertThat(g.identity("Main/s3~3/a1~2")).isEqualTo("Main/s3~3/a1");
        assertThat(g.identity("Main/s0")).isEqualTo("Main/s0");
        assertThat(Generations.strip("Main/s3~3/a1~2")).isEqualTo("Main/s3/a1");
    }

    @Test
    @Tag("RW-V4.2")
    void anOperatorsAskAgainReachesTheStepsOfThatGenerationOnly() {
        Generations g = new Generations(RunJournal.inMemory());
        start(g, "Main/s", 1, "keep", true);
        start(g, "Main/s", 1, "keep", false);
        assertThat(g.asksAgain("Main/s1~2/a0")).isTrue();
        assertThat(g.asksAgain("Main/s1~3/a0")).isFalse();
        assertThat(g.asksAgain("Main/s1/a0")).isFalse();
        assertThat(g.asksAgain("Elsewhere/s1~2")).isFalse();
    }

    @Test
    @Tag("RW-V3.5")
    void aStatementIsLocatedAtItsLiveIdAndNonStatementsAreRefusedNotGuessed() {
        Generations g = new Generations(RunJournal.inMemory());
        start(g, "Main/s", 2, "keep", false);
        start(g, "Main/s2~2/a", 0, "keep", false);

        assertThat(g.locate("Main/s2").liveId()).isEqualTo("Main/s2~2");
        assertThat(g.locate("Main/s2/a1").liveId()).isEqualTo("Main/s2~2/a1~2");
        assertThat(g.locate("Main/s1").liveId()).isEqualTo("Main/s1");
        assertThat(g.locate("Main/s2/a1").block()).isEqualTo("Main/s2~2/a");
        assertThat(g.locate("Main")).isNull();
        assertThat(g.locate("Main>Sub/s1")).isNull();
        assertThat(g.locate("Main/s2/e0")).isNull();
        assertThat(g.locate("Main/s2/p1")).isNull();
        assertThat(g.locate("Main/garbage")).isNull();
    }

    @Test
    @Tag("RW-V3.6")
    void aStepIsOnlyCurrentWhileItsBlockHasNotMovedOnToANewerGeneration() {
        Generations g = new Generations(RunJournal.inMemory());
        start(g, "Main/s", 2, "keep", false);
        assertThat(g.isCurrent("Main/s2")).isFalse();
        assertThat(g.isCurrent("Main/s2~2")).isTrue();
        assertThat(g.isCurrent("Main/s1")).isTrue();
        assertThat(g.isCurrent("Main/s2~2/a0")).isTrue();
        assertThat(g.isCurrent("Main/s2/a0")).isFalse();
        assertThat(g.isCurrent("Main")).isTrue();
        assertThat(g.isCurrent("Main>Sub/s0")).isTrue();
        assertThat(g.isCurrent("Main/s2~2/e0")).isTrue();
        assertThat(g.isCurrent("Main/oops")).isTrue();
    }

    @Test
    @Tag("RW-V4.4")
    void theScanFindsEffectsDoneOrUnknownAndApprovalsGivenFromAStatementOnInAnyAttempt() {
        RunJournal j = RunJournal.inMemory();
        j.put("Main/s2#effect:mail:abc", new RunJournal.Entry("effect_done", "x"));
        j.put("Main/s3~2#effect:post:def", new RunJournal.Entry("effect_pending", "x"));
        j.put("Main/s1#effect:early:ghi", new RunJournal.Entry("effect_done", "x"));
        j.put("Main/s4#effect:other:jkl", new RunJournal.Entry("note", "x"));
        j.put("Main/s5#unclassified:Mystery", new RunJournal.Entry("note", "x"));
        j.put("Elsewhere/s9#effect:mail:zzz", new RunJournal.Entry("effect_done", "x"));
        j.put("Main/sx#effect:bad:aaa", new RunJournal.Entry("effect_done", "x"));
        j.put("unrelated", new RunJournal.Entry("note", "x"));
        j.put("Main/s2#approve:Mail:hash", new RunJournal.Entry("approval", " YES "));
        j.put("Main/s3~2#approve:Post:hash", new RunJournal.Entry("approval", "no"));
        j.put("Main/s0#approve:Early:hash", new RunJournal.Entry("approval", "ok"));

        assertThat(EffectScan.effectsIn(j, "Main/s", 2)).containsExactlyInAnyOrder(
                "mail at Main/s2 (done)", "post at Main/s3 (outcome unknown)",
                "Mystery at Main/s5 (a tool that isn't known to change nothing)");
        assertThat(EffectScan.approvalsIn(j, "Main/s", 1)).containsExactly("Mail at Main/s2");
        assertThat(EffectScan.inRegion("Main/s", "Main/s", 0)).isFalse();
        assertThat(EffectScan.inRegion("Other/s2", "Main/s", 0)).isFalse();
    }
}
