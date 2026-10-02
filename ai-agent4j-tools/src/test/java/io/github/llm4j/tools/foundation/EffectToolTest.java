package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.support.FakeEffectful;
import io.github.llm4j.tools.support.FaultJournal;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.tools.CanonicalArgs;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.tools.EffectTool;
import io.github.llm4j.agent.tool.Outcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The effect journal (requirement 2): a resumed run never repeats what already happened. */
class EffectToolTest {

    static final String REPLAYED = "(already done in an earlier attempt) ";

    final EffectJournal journal = EffectJournal.inMemory();
    final RecordingEffects ctx = new RecordingEffects(journal);
    final FakeEffectful fake = new FakeEffectful("Slack");

    EffectTool tool() {
        return new EffectTool(fake, ctx);
    }

    static Map<String, Object> args(String text) {
        return Map.of("text", text);
    }

    String keyFor(String text, int n) {
        return "main/s0#effect:Slack:" + CanonicalArgs.hash12("Slack", args(text)) + "#" + n;
    }

    @Test
    @Tag("V2.1")
    void writesPendingBeforeActingAndDoneAfter() {
        List<String> seenWhileActing = new java.util.ArrayList<>();
        fake.next = () -> {
            seenWhileActing.add(journal.get(keyFor("hi", 0)).map(EffectJournal.Entry::kind).orElse("none"));
            return Outcome.ok("sent");
        };

        assertThat(tool().execute(args("hi"))).isEqualTo("sent");

        assertThat(seenWhileActing).containsExactly("effect_pending");
        assertThat(journal.get(keyFor("hi", 0))).get().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("effect_done");
            assertThat(e.value()).isEqualTo("sent");
        });
    }

    @Test
    @Tag("V2.2")
    void aResumedRunReturnsTheRecordedResultAndDoesNotActAgain() {
        tool().execute(args("hi"));
        EffectTool afterRestart = tool(); // a new executor, same journal

        String again = afterRestart.execute(args("hi"));

        assertThat(fake.performed).hasSize(1);
        assertThat(again).startsWith(REPLAYED).endsWith("done");
        assertThat(ctx.audited("tool_effect")).extracting(e -> e.data().get("outcome")).containsExactly("ok", "replayed");
    }

    @Test
    @Tag("V2.3")
    void anUnknownOutcomeIsNotRepeatedByDefault() {
        // The process died after acting and before recording "done": exactly one put (pending) succeeds.
        EffectTool crashing = new EffectTool(fake, new RecordingEffects(new FaultJournal(journal, 1, false)));
        assertThatThrownBy(() -> crashing.execute(args("hi"))).isInstanceOf(FaultJournal.SimulatedCrash.class);
        assertThat(fake.performed).hasSize(1);

        String resumed = tool().execute(args("hi"));

        assertThat(fake.performed).as("not repeated").hasSize(1);
        assertThat(resumed).startsWith("Error:").contains("outcome is unknown");
        assertThat(ctx.audited("effect_unknown")).hasSize(1);
    }

    @Test
    @Tag("V2.4")
    void onUnknownRetryRepeatsIt() {
        EffectTool crashing = new EffectTool(fake, new RecordingEffects(new FaultJournal(journal, 1, false)));
        assertThatThrownBy(() -> crashing.execute(args("hi"))).isInstanceOf(FaultJournal.SimulatedCrash.class);
        fake.policy = new EffectPolicy(EffectPolicy.OnUnknown.RETRY, false, 0);

        assertThat(tool().execute(args("hi"))).isEqualTo("done");

        assertThat(fake.performed).hasSize(2);
        assertThat(journal.get(keyFor("hi", 0))).get().extracting(EffectJournal.Entry::kind).isEqualTo("effect_done");
    }

    @Test
    @Tag("V2.3")
    void anOutcomeTheToolReportsAsUnknownStaysPending() {
        fake.next = () -> Outcome.unknown("timed out; may have been delivered");

        assertThat(tool().execute(args("hi"))).contains("may have been delivered");

        assertThat(journal.get(keyFor("hi", 0))).get().extracting(EffectJournal.Entry::kind).isEqualTo("effect_pending");
        fake.next = () -> Outcome.ok("done");
        assertThat(tool().execute(args("hi"))).contains("outcome is unknown");
    }

    @Test
    @Tag("V2.5")
    void idempotentToolsAlwaysRetryWithTheSameKeyAcrossAResume() {
        fake.policy = new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0);
        fake.next = () -> Outcome.unknown("lost");
        tool().execute(args("hi"));
        tool().execute(args("hi")); // resumed: pending found, idempotent, so tried again

        assertThat(fake.performed).hasSize(2);
        assertThat(fake.idempotencyKeys.get(0)).isNotBlank().hasSize(32).isEqualTo(fake.idempotencyKeys.get(1));
    }

    @Test
    @Tag("V2.5")
    void idempotencyKeysDifferBetweenRunsOfTheSameWorkflow() {
        fake.policy = new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0);
        tool().execute(args("hi"));
        FakeEffectful other = new FakeEffectful("Slack");
        other.policy = fake.policy;
        new EffectTool(other, new RecordingEffects()).execute(args("hi")); // tomorrow's run: a different journal

        assertThat(other.idempotencyKeys.get(0)).isNotEqualTo(fake.idempotencyKeys.get(0));
    }

    @Test
    @Tag("V2.6")
    void differentArgumentsRunAndIdenticalCallsRunTwiceButReplayInOrder() {
        int[] n = {0};
        fake.next = () -> Outcome.ok("result-" + n[0]++);
        EffectTool first = tool();
        first.execute(args("a"));
        first.execute(args("b"));
        first.execute(args("a")); // identical to the first call, in the same step: a second send
        assertThat(fake.performed).hasSize(3);

        EffectTool resumed = tool();
        assertThat(resumed.execute(args("a"))).endsWith("result-0");
        assertThat(resumed.execute(args("b"))).endsWith("result-1");
        assertThat(resumed.execute(args("a"))).endsWith("result-2");
        assertThat(fake.performed).as("nothing repeated").hasSize(3);
    }

    @Test
    @Tag("V2.7")
    void aFailedCallIsNotReportedAsDoneAndTheRetryRunsAgain() {
        fake.next = () -> Outcome.failed("bad request");
        EffectTool t = tool();
        assertThat(t.execute(args("hi"))).startsWith("Error:");
        assertThat(journal.get(keyFor("hi", 0))).get().extracting(EffectJournal.Entry::kind).isEqualTo("effect_failed");

        fake.next = () -> Outcome.ok("fixed");
        assertThat(t.execute(args("hi"))).isEqualTo("fixed");

        assertThat(fake.performed).hasSize(2);
        assertThat(journal.get(keyFor("hi", 0))).get().extracting(EffectJournal.Entry::kind).isEqualTo("effect_done");
        assertThat(journal.get(keyFor("hi", 1))).as("the retry reused the number").isEmpty();
    }

    @Test
    @Tag("V2.8")
    void readsPassThroughAndLeaveNoRecord() {
        fake.effect = false;
        assertThat(tool().execute(args("hi"))).isEqualTo("done");
        assertThat(tool().execute(args("hi"))).isEqualTo("done");
        assertThat(fake.performed).hasSize(2);
        assertThat(journal.all()).isEmpty();
    }

    @Test
    @Tag("V2.9")
    void differentStepsHaveSeparateRecords() {
        EffectTool t = tool();
        ctx.step = "main/s0/p0";
        t.execute(args("hi"));
        ctx.step = "main/s0/p1";
        t.execute(args("hi"));
        assertThat(fake.performed).hasSize(2);
        assertThat(journal.all().keySet()).anyMatch(k -> k.startsWith("main/s0/p0#effect:"))
                .anyMatch(k -> k.startsWith("main/s0/p1#effect:"));
    }

    @Test
    @Tag("V2.6")
    void aNewDelegateAttemptNumbersItsCallsAfresh() {
        EffectTool t = tool();
        t.execute(args("hi"));
        ctx.attempt.incrementAndGet(); // the delegate is retried in the same step, on the same thread
        String again = t.execute(args("hi"));

        assertThat(again).startsWith("(already done");
        assertThat(fake.performed).hasSize(1);
    }

    @Test
    @Tag("V5.4")
    void maxPerRunCountsDoneAndUnknownButNotFailedAttempts() {
        fake.policy = new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 2);
        EffectTool t = tool();
        fake.next = () -> Outcome.failed("nope");
        t.execute(args("a"));
        t.execute(args("b"));
        fake.next = () -> Outcome.ok("ok");
        assertThat(t.execute(args("c"))).isEqualTo("ok");
        assertThat(t.execute(args("d"))).isEqualTo("ok");
        assertThat(t.execute(args("e"))).startsWith("Error:").contains("limited to 2 calls per run");

        // After a restart the count comes from the journal, not from memory.
        assertThat(tool().execute(args("f"))).contains("limited to 2");
        assertThat(fake.performed).hasSize(4);
    }

    @Test
    @Tag("V5.12")
    void anAttemptWhoseOutcomeIsUnknownUsesUpTheAllowanceBecauseItMayHaveBeenSent() {
        fake.policy = new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 1);
        fake.next = () -> Outcome.unknown("the connection was lost");
        tool().execute(args("a"));

        fake.next = () -> Outcome.ok("ok");
        assertThat(tool().execute(args("b"))).startsWith("Error:").contains("limited to 1 calls per run");
        assertThat(fake.performed).hasSize(1);
    }

    @Test
    @Tag("V1.11")
    void auditAndTraceNameTheTargetAndOutcomeButNotTheArguments() {
        tool().execute(Map.of("text", "the confidential message body"));

        assertThat(ctx.audited("tool_effect")).hasSize(1).first().satisfies(e -> {
            assertThat(e.data()).containsEntry("tool", "Slack").containsEntry("target", "fake-target").containsEntry("outcome", "ok");
        });
        assertThat(ctx.everything()).doesNotContain("confidential");
    }

    // ---- a host that can run a step again: an attempt is not part of an effect's identity ------------------

    @Test
    @org.junit.jupiter.api.Tag("RW-V4.1")
    @org.junit.jupiter.api.Tag("RW-V4.11")
    void anIdenticalCallInALaterAttemptOfTheSameStepIsFoundNotRepeatedAndOrdinalsHold() {
        ctx.step = "W/s1";
        ctx.identity = "W/s1";
        EffectTool tool = tool();
        assertThat(tool.execute(args("same"))).doesNotContain(REPLAYED);
        assertThat(tool.execute(args("same"))).doesNotContain(REPLAYED); // a second identical call in one attempt is its own call (ordinal 1)
        assertThat(fake.performed).hasSize(2);

        ctx.attempt.incrementAndGet();
        ctx.step = "W/s1~2"; // the same place, a later attempt; its identity is the place
        assertThat(tool.execute(args("same"))).startsWith(REPLAYED);
        assertThat(tool.execute(args("same"))).startsWith(REPLAYED);
        assertThat(fake.performed).as("neither was performed again").hasSize(2);
        assertThat(journal.all().keySet().stream().filter(k -> k.contains("#effect:")).toList()).containsExactlyInAnyOrder(
                "W/s1#effect:Slack:" + hash("same") + "#0",
                "W/s1#effect:Slack:" + hash("same") + "#1");

        assertThat(tool.execute(args("different"))).doesNotContain(REPLAYED);
        assertThat(fake.performed).hasSize(3);
        assertThat(ctx.audited("effect_after_rewind")).hasSize(1);
        assertThat(ctx.trace).anyMatch(t -> t.startsWith("a different effect after a rewind: Slack"));
    }

    private static String hash(String text) {
        return io.github.llm4j.tools.CanonicalArgs.hash12("Slack", args(text));
    }

    @Test
    @org.junit.jupiter.api.Tag("RW-V4.8")
    void aSimulatedContextPerformsNoEffectAndRecordsNothingAsDoneButLetsReadsThrough() {
        ctx.simulating = true;
        EffectTool tool = tool();
        assertThat(tool.execute(args("x"))).isEqualTo(EffectTool.SIMULATED);
        assertThat(fake.performed).isEmpty();
        assertThat(journal.all()).isEmpty();
        assertThat(ctx.audited("tool_effect")).hasSize(1);

        fake.effect = false;
        assertThat(tool.execute(args("x"))).isEqualTo("done");
        assertThat(fake.performed).hasSize(1);
    }
}
