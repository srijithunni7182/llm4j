package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.Trigger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A question that waits, and the ways it is answered (spec loom-remote-answers R1, R2, R4). */
class QuestionFlowTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    HumanInterface person;
    static final String RUN = "/srv/runs/triage-42";

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
        person = h.asker(RUN);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    Pending only() {
        assertThat(h.pending.all()).hasSize(1);
        return h.pending.all().get(0);
    }

    @Test
    @Tag("RA-V1.1")
    void aQuestionIsRecordedSentOnceAndTheRunSuspendsHoldingNothing() {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish today's digest? (yes/no)")).isInstanceOf(RunSuspended.class)
                .satisfies(e -> assertThat(((RunSuspended) e).reason()).isEqualTo(RunSuspended.Reason.HUMAN));

        Pending p = only();
        assertThat(p.state()).isEqualTo(Pending.State.OPEN);
        assertThat(p.sent()).hasSize(1);
        assertThat(h.telegram.sent).hasSize(1);
        assertThat(h.telegram.last().chat()).isEqualTo(ChannelHarness.ME);
    }

    @Test
    @Tag("RA-V1.2")
    void everyKindOfQuestionTakesTheSamePath() {
        Hints approval = new Hints(Hints.Kind.APPROVAL, List.of("yes", "no"), null);
        Hints decide = new Hints(Hints.Kind.DECIDE, List.of("approve", "reject"), "support-lead");
        for (Object[] q : new Object[][] {{"A/s0", "free text?", Hints.none()}, {"A/s1#approve:Mail:1", "Agent wants to call Mail. Approve? yes/no", approval},
                {"A/s2#decide-ask", "Decide Refund", decide}, {"A/s3#rewind-ask", "Going back would repeat effects", new Hints(Hints.Kind.APPROVAL, List.of("keep", "repeat", "cancel"), null)}}) {
            assertThatThrownBy(() -> person.promptHuman((String) q[0], (String) q[1], (Hints) q[2])).isInstanceOf(RunSuspended.class);
        }
        assertThat(h.pending.all()).extracting(Pending::kind).containsExactlyInAnyOrder("prompt", "approval", "decide", "approval");
        assertThat(h.telegram.sent).hasSize(4);
    }

    @Test
    @Tag("RA-V1.3")
    @Tag("RA-V1.4")
    void resumingWithoutAnAnswerSendsNothingAndWithOneReturnsItWithoutSendingAgain() {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.sent).as("no second message while it waits").hasSize(1);

        assertThat(h.answers.record(only().code(), "yes", "test").recorded()).isTrue();

        assertThat(person.promptHuman("Main/s0", "Publish?")).isEqualTo("yes");
        assertThat(h.telegram.sent).hasSize(1);
    }

    @Test
    @Tag("RA-V1.5")
    void theMessageNamesTheRunHoldsTheQuestionTheCodeAndTheWordsToReplyWith() {
        assertThatThrownBy(() -> person.promptHuman("Triage/s0#decide-ask", "support-lead, please decide Refund (approve / reject / escalate)\namount = 50",
                new Hints(Hints.Kind.DECIDE, List.of("approve", "reject", "escalate"), "support-lead"))).isInstanceOf(RunSuspended.class);

        String text = h.telegram.last().text();
        assertThat(text).startsWith("[triage-42] support-lead, please decide Refund").contains("amount = 50").contains(only().code() + " approve | reject | escalate");
        assertThat(only().code()).hasSize(Codes.LENGTH);
    }

    @Test
    @Tag("RA-V1.8")
    void everyCrashPointLeavesOneAnswerAndAtMostADuplicateMessageWithTheSameCode() {
        // killed after the record was written and before the send: the next ask sends it
        Pending unsent = new Pending(h.pending.freshCode(), RUN, "run:" + RUN, "Main/s9", "Q9?", List.of(), "prompt", null, ChannelHarness.T0);
        h.pending.locked(() -> {
            h.pending.put(unsent);
            return null;
        });
        assertThatThrownBy(() -> person.promptHuman("Main/s9", "Q9?")).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.sent).hasSize(1);

        // killed after the send and before it was marked sent: the next ask sends the same code again
        Pending lost = h.pending.get(unsent.code()).orElseThrow();
        h.pending.locked(() -> {
            Pending fresh = new Pending(lost.code(), lost.run(), lost.runId(), lost.step(), lost.question(), lost.choices(), lost.kind(), lost.to(), lost.createdAt());
            h.pending.put(fresh);
            return null;
        });
        assertThatThrownBy(() -> person.promptHuman("Main/s9", "Q9?")).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.sent).hasSize(2);
        assertThat(h.telegram.sent.get(0).text()).contains(unsent.code());
        assertThat(h.telegram.sent.get(1).text()).contains(unsent.code());

        // the answer applies once, however many messages went out
        assertThat(h.answers.record(unsent.code(), "ok", "a").recorded()).isTrue();
        assertThat(h.answers.record(unsent.code(), "ok", "b").recorded()).isFalse();
        assertThat(person.promptHuman("Main/s9", "Q9?")).isEqualTo("ok");
        assertThat(h.triggers.all()).hasSize(1);
    }

    @Test
    @Tag("RA-V2.1")
    void anAnswerLeavesOneResumeTriggerForTheRun() {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);

        h.answers.record(only().code(), "yes", "operator:me");
        h.answers.record(only().code(), "yes", "operator:me");

        assertThat(h.triggers.all()).hasSize(1);
        Trigger t = h.triggers.all().get(0);
        assertThat(t.id()).isEqualTo(Trigger.resumeId("run:" + RUN));
        assertThat(t.target()).isEqualTo(new Trigger.ResumeRun("run:" + RUN));
    }

    @Test
    @Tag("RA-V2.3")
    void aSecondAnswerIsRefusedAndNamesWhoWasFirst() {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        String code = only().code();

        assertThat(h.answers.record(code, "yes", "telegram:1").recorded()).isTrue();
        Answers.Outcome second = h.answers.record(code, "no", "operator:bob");

        assertThat(second.recorded()).isFalse();
        assertThat(second.message()).contains("already answered by telegram:1");
        assertThat(only().answer().text()).isEqualTo("yes");
    }

    @Test
    @Tag("RA-V2.4")
    void aTextThatMatchesNoChoiceIsNotRecordedAndAUniquePrefixIs() {
        assertThatThrownBy(() -> person.promptHuman("D/s0#decide-ask", "Decide?", new Hints(Hints.Kind.DECIDE, List.of("approve", "reject", "escalate"), null))).isInstanceOf(RunSuspended.class);
        String code = only().code();

        Answers.Outcome bad = h.answers.record(code, "maybe", "t");
        assertThat(bad.recorded()).isFalse();
        assertThat(bad.message()).contains("approve, reject, escalate");
        assertThat(only().state()).isEqualTo(Pending.State.OPEN);
        assertThat(h.answers.record(code, "ap", "t").message()).contains("approve");
        assertThat(only().answer().text()).isEqualTo("approve");
    }

    @Test
    @Tag("RA-V2.5")
    void anAcceptedAnswerIsAuditedWithWhoWhenAndWhat() throws Exception {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        h.clock.advance(Duration.ofMinutes(3));

        h.answers.record(only().code(), "yes", "telegram:5550001");

        String audit = Files.readString(h.pending.root().resolve("audit.jsonl"));
        assertThat(audit).contains("\"event\":\"answered\"").contains(only().code()).contains("telegram:5550001").contains("\"answer\":\"yes\"").contains("2026-04-01T08:03:00Z");
        assertThat(person.promptHuman("Main/s0", "Publish?")).isEqualTo("yes");
    }

    @Test
    @Tag("RA-V2.6")
    void anUnknownExpiredOrAnsweredCodeChangesNothingAndSaysWhy() {
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        Pending p = only();

        assertThat(h.answers.record("ZZZZZZ", "yes", "t").message()).contains("No question has the code");
        assertThat(h.answers.record("../../etc", "yes", "t").message()).contains("No question has the code");
        h.answers.expire(p);
        assertThat(h.answers.record(p.code(), "yes", "t").message()).contains("expired");
        assertThat(only().state()).isEqualTo(Pending.State.EXPIRED);
        assertThat(only().answer()).isNull();
    }

    @Test
    @Tag("RA-V4.4")
    void aQuestionIsRemindedUnderTheSameCodeAtMostTheConfiguredNumberOfTimes() throws Exception {
        Channels.Runtime rt = h.runtimeWith(h.config(Duration.ofHours(6), 2, null, Map.of("default", List.of(ChannelHarness.ME))));
        assertThatThrownBy(() -> rt.humanFor(Path.of(RUN), "run:" + RUN).promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        String code = only().code();

        for (int hours : new int[] {5, 7, 14, 21, 28}) {
            h.clock.set(ChannelHarness.T0.plus(Duration.ofHours(hours)));
            rt.listener().maintain();
        }

        assertThat(h.telegram.sent).as("the first send and two reminders").hasSize(3);
        assertThat(h.telegram.sent).allSatisfy(s -> assertThat(s.text()).contains(code));
        assertThat(only().reminders()).isEqualTo(2);
    }

    @Test
    @Tag("RA-V4.5")
    void anExpiredQuestionIsClosedTheRunIsToldToCarryOnAndTheStepGetsAnEmptyAnswer() throws Exception {
        Channels.Runtime rt = h.runtimeWith(h.config(null, 0, Duration.ofDays(3), Map.of("default", List.of(ChannelHarness.ME))));
        HumanInterface asker = rt.humanFor(Path.of(RUN), "run:" + RUN);
        assertThatThrownBy(() -> asker.promptHuman("Main/s0", "Publish?")).isInstanceOf(RunSuspended.class);
        String code = only().code();

        h.clock.advance(Duration.ofDays(4));
        rt.listener().maintain();

        assertThat(only().state()).isEqualTo(Pending.State.EXPIRED);
        assertThat(h.triggers.all()).hasSize(1);
        assertThat(asker.promptHuman("Main/s0", "Publish?")).isEmpty();
        assertThat(h.telegram.last().text()).contains("expired").contains(code);
        assertThat(h.answers.record(code, "yes", "t").recorded()).isFalse();
    }

    @Test
    @Tag("RA-V4.7")
    void theNameAScriptAsksIsRoutedToItsChatsAndAnUnmappedNameGoesToTheDefault() throws Exception {
        Channels.Runtime rt = h.runtimeWith(h.config(null, 0, null, Map.of("default", List.of(ChannelHarness.ME), "support-lead", List.of(777L, 778L))));
        HumanInterface asker = rt.humanFor(Path.of(RUN), "run:" + RUN);

        assertThatThrownBy(() -> asker.promptHuman("A/s0", "to the lead", new Hints(Hints.Kind.DECIDE, List.of("approve"), "support-lead"))).isInstanceOf(RunSuspended.class);
        assertThatThrownBy(() -> asker.promptHuman("A/s1", "to nobody in particular", new Hints(Hints.Kind.DECIDE, List.of("approve"), "someone-else"))).isInstanceOf(RunSuspended.class);

        assertThat(h.telegram.sentTo(777)).hasSize(1);
        assertThat(h.telegram.sentTo(778)).hasSize(1);
        assertThat(h.telegram.sentTo(ChannelHarness.ME)).hasSize(1).allSatisfy(s -> assertThat(s.text()).contains("to nobody in particular"));
    }
}
