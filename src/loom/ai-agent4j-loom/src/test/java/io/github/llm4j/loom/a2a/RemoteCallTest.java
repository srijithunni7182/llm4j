package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.testkit.MockA2AAgent;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Script;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Step;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Loom script calling a remote agent: against the mock A2A 1.0 agent over loopback, through the
 * real helper and a test transport. Each run of the workflow is a new executor over the same
 * journal, which is what a host does when it resumes a run.
 */
class RemoteCallTest {

    private static final String PUBLIC = "https://agent.example.test";

    private static final String SCRIPT = """
            agent_channel Desk { audience: staff max_questions: 3 timeout: 5m }
            remote Supplier {
                card: "https://agent.example.test/.well-known/agent-card.json"
                agent_channel: Desk
                timeout: 2h
            }
            workflow Buy(part) {
                delegate "Quote 500 units of {part}" to Supplier -> quote
            }
            """;

    @TempDir Path dir;

    private MockA2AAgent mock;
    private LoopbackTransport transport;
    private final RunJournal journal = RunJournal.inMemory();
    private final List<String> questions = new ArrayList<>();
    private final List<TraceEvent> traces = new ArrayList<>();
    private Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private AgentChannel channel = (context, message) -> {
        questions.add(message.toString());
        return new ChannelResponse.Reply("Within 30 days.", null);
    };

    @BeforeEach
    void start() {
        mock = MockA2AAgent.start(PUBLIC);
        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase());
    }

    @AfterEach
    void stop() {
        assertThat(mock.selfCheckFailures()).isEmpty();
        mock.close();
    }

    private HarnessExecutor executor(String source) {
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = journal;
        HarnessExecutor e = run.executor(source);
        e.setA2ATransport(transport);
        e.setAgentChannel("Desk", channel);
        e.setClock(clock);
        e.addTraceListener(traces::add);
        return e;
    }

    private Object runOnce(String source) {
        HarnessExecutor e = executor(source);
        e.executeWorkflow("Buy", Map.of("part", "12"));
        return e.getContext().getAll().get("quote");
    }

    @Test
    void aCallThatCompletesAtOnceBindsTheTextAndAResumeSendsNothingAgain() {
        mock.script(Script.completesAtOnce("42 each"));

        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
        assertThat(mock.receivedMessageIds()).hasSize(1);

        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
        assertThat(mock.receivedMessageIds()).as("a replay never resends").hasSize(1);
    }

    @Test
    void aPlainMessageReplyEndsTheStepAtOnce() {
        mock.script(Script.plainMessage("42 each"));
        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
        assertThat(mock.taskCount()).isZero();
    }

    @Test
    void aTaskThatIsStillWorkingSuspendsTheRunAndHoldsNoThread() {
        mock.script(Script.of(Step.working(), Step.working(), Step.completedWithText("42 each")));

        assertThatThrownBy(() -> runOnce(SCRIPT))
                .isInstanceOfSatisfying(RunSuspended.class, s -> {
                    assertThat(s.reason()).isEqualTo(RunSuspended.Reason.REMOTE);
                    assertThat(s.resumeAt()).isEqualTo(Instant.parse("2026-11-07T12:00:00Z"));
                });
        assertThat(mock.receivedMessageIds()).hasSize(1);
    }

    @Test
    void aQuestionGoesToTheChannelAndTheAnswerIsSentOnTheSameTask() {
        mock.script(Script.of(Step.working(), Step.asks("Which date?"), Step.working(), Step.completedWithText("42 each")));

        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class); // working
        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class); // asked, answered, working again

        assertThat(questions).hasSize(1).first().asString().contains("untrusted text");
        assertThat(mock.receivedMessageIds()).hasSize(2); // the goal and the answer

        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
        assertThat(mock.received().stream().filter(r -> r.method().equals("SendMessage")).count()).isEqualTo(2);
    }

    @Test
    void aFailedTaskFailsTheStepAndOnFailureRunsWithTheRemotesMessageShortened() {
        mock.script(Script.of(Step.failed("out of stock")));
        String source = SCRIPT.replace("-> quote\n", "-> quote\n    on_failure { halt \"supplier said no\" }\n");
        assertThatThrownBy(() -> runOnce(source)).hasMessageContaining("supplier said no");
        assertThat(traces.stream().map(TraceEvent::type)).contains(TraceEvent.REMOTE);
    }

    @Test
    void aCardPointingElsewhereIsRefusedAndNothingIsSent() {
        mock.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("evil.example.net");
        assertThat(mock.received()).isEmpty();
    }

    @Test
    void aCrashAfterTheSendNeverMakesASecondTaskTheTaskIsFoundByItsMessageId() {
        mock.script(Script.of(Step.working(), Step.completedWithText("42 each")));
        mock.mode(MockA2AAgent.Mode.DROP_AFTER_RECEIVE);
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("not known whether it arrived");
        assertThat(mock.taskCount()).isEqualTo(1);

        mock.mode(MockA2AAgent.Mode.NONE);
        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class); // found, still working
        assertThat(mock.taskCount()).as("no second remote task").isEqualTo(1);
        assertThat(mock.receivedMessageIds()).hasSize(1);

        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
    }

    @Test
    void whenTheServerWillNotListTheStepStopsWithAMessageAndSendsNothing() {
        mock.mode(MockA2AAgent.Mode.DROP_AFTER_RECEIVE);
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("not known whether it arrived");
        mock.mode(MockA2AAgent.Mode.NONE).listingRefused(true);
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("nothing was sent again");
        assertThat(mock.receivedMessageIds()).hasSize(1);
    }

    @Test
    void aStateThatNeverBecomesKnownFailsTheStepAtTheDeadline() {
        mock.mode(MockA2AAgent.Mode.UNKNOWN_STATE);
        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class);
        clock = Clock.fixed(Instant.parse("2026-11-07T12:30:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("did not finish in time");
    }

    private HarnessExecutor stopWhenAsked() {
        java.util.concurrent.atomic.AtomicReference<HarnessExecutor> self = new java.util.concurrent.atomic.AtomicReference<>();
        channel = (c, m) -> {
            self.get().control().request(io.github.llm4j.loom.execution.RunControl.Action.STOP, "alice");
            return new ChannelResponse.Reply("Within 30 days.", null);
        };
        HarnessExecutor e = executor(SCRIPT);
        self.set(e);
        return e;
    }

    @Test
    void aStoppedRunAsksTheRemoteToCancelItsTaskAndTheCancelIsJournaled() {
        mock.script(Script.of(Step.working(), Step.asks("Which date?"), Step.working(), Step.completedWithText("42 each")));
        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class); // the task now runs there

        HarnessExecutor stopping = stopWhenAsked();
        assertThatThrownBy(() -> stopping.executeWorkflow("Buy", Map.of("part", "12"))).isInstanceOf(io.github.llm4j.loom.runtime.RunStopped.class);

        assertThat(mock.received().stream().map(r -> r.method())).contains("CancelTask");
        assertThat(traces.stream().map(t -> String.valueOf(t.data().get("kind")))).contains("cancel");
        assertThat(journal.get("Buy/s0#r0/cancelled").orElseThrow().value()).isEqualTo("ok");
    }

    @Test
    void aCancelThatFailsIsRecordedAndDoesNotBlockTheStop() {
        mock.script(Script.of(Step.working(), Step.asks("Which date?"), Step.working(), Step.completedWithText("42 each")));
        assertThatThrownBy(() -> runOnce(SCRIPT)).isInstanceOf(RunSuspended.class);

        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase()) {
            @Override
            public io.github.llm4j.a2a.RemoteTask cancel(io.github.llm4j.a2a.AgentCardView.Endpoint endpoint, io.github.llm4j.secret.SecretRef credential, String taskId) {
                throw new io.github.llm4j.a2a.A2AException("the remote would not cancel");
            }
        };
        HarnessExecutor stopping = stopWhenAsked();
        assertThatThrownBy(() -> stopping.executeWorkflow("Buy", Map.of("part", "12"))).isInstanceOf(io.github.llm4j.loom.runtime.RunStopped.class);

        assertThat(traces.stream().map(t -> String.valueOf(t.data().get("kind")))).contains("cancel_failed");
        assertThat(journal.get("Buy/s0#r0/cancelled").orElseThrow().value()).isEqualTo("failed");
    }

    @Test
    void aPlainMessageReplyIsNotSentAgainOnAResume() {
        mock.script(Script.plainMessage("42 each"));
        assertThat(runOnce(SCRIPT)).isEqualTo("42 each");
        assertThat(runOnce(SCRIPT)).as("replayed from the journal, no task to find").isEqualTo("42 each");
        assertThat(mock.receivedMessageIds()).hasSize(1);
    }

    @Test
    void whenNoRecentTaskHoldsTheMessageIdTheStepStopsAndSendsNothing() {
        // the send never reached the server: nothing there can be found, and nothing may be sent blindly
        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase()) {
            @Override
            public io.github.llm4j.a2a.SendResult send(io.github.llm4j.a2a.AgentCardView.Endpoint endpoint, io.github.llm4j.secret.SecretRef credential, io.github.llm4j.a2a.A2AMessage message, io.github.llm4j.a2a.SendOptions options) {
                throw new io.github.llm4j.a2a.A2AException("connection reset");
            }
        };
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("not known whether it arrived");
        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase());
        assertThatThrownBy(() -> runOnce(SCRIPT)).hasMessageContaining("no recent task").hasMessageContaining("nothing was sent again");
        assertThat(mock.receivedMessageIds()).isEmpty();
        assertThat(mock.taskCount()).isZero();
    }

    @Test
    void aRefusalThatCannotChangeIsNeverRetriedSoTheCardIsFetchedOnce() {
        int[] cardFetches = {0};
        mock.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
        transport = new LoopbackTransport(PUBLIC, mock.loopbackBase()) {
            @Override
            public io.github.llm4j.a2a.AgentCardView fetchCard(String address) {
                cardFetches[0]++;
                return super.fetchCard(address);
            }
        };
        assertThatThrownBy(() -> runOnce(SCRIPT.replace("-> quote\n", "-> quote retry 3\n"))).hasMessageContaining("evil.example.net");
        assertThat(cardFetches[0]).isEqualTo(1);
    }

    @Test
    void whatTheRemoteWroteIsUncheckedSoItCannotLeaveToTheRemoteAgainWhenAPolicyIsDeclared() throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("policies"));
        java.nio.file.Files.writeString(dir.resolve("policies/house-rules.md"), "# House\n\n## no_promise\nThe reply promises a refund.\n");
        String guarded = """
                evaluator Compliance { model: "judge" }
                policy HouseRules: "house-rules" checked by Compliance
                """ + SCRIPT.replace("-> quote\n", "-> quote\n    delegate \"confirm {quote}\" to Supplier -> again\n");
        mock.script(Script.completesAtOnce("42 each"));
        assertThatThrownBy(() -> runOnce(guarded)).hasMessageContaining("has not been checked").hasMessageContaining("quote");
        assertThat(mock.receivedMessageIds()).as("only the first message left").hasSize(1);
    }

    @Test
    void aRemoteStepIsNotCountedByABudgetBecauseItsCostIsOutsideWhatLoomSees() {
        mock.script(Script.completesAtOnce("42 each"));
        String budgeted = "budget { calls: 1 }\n" + SCRIPT.replace("-> quote\n", "-> quote\n    delegate \"again {quote}\" to Supplier -> second\n");
        runOnce(budgeted); // two remote calls under a budget of one call: nothing is counted, nothing is refused
        assertThat(mock.receivedMessageIds()).hasSize(2);
    }

    @Test
    void aMissingTransportOrChannelFailsClosedBeforeAnythingIsSent() {
        HarnessExecutor noTransport = new ScriptedRun(dir).executor(SCRIPT);
        noTransport.setAgentChannel("Desk", channel);
        assertThatThrownBy(() -> noTransport.executeWorkflow("Buy", Map.of("part", "12"))).hasMessageContaining("has no A2A transport");
        HarnessExecutor noChannel = new ScriptedRun(dir).executor(SCRIPT);
        noChannel.setA2ATransport(transport);
        assertThatThrownBy(() -> noChannel.executeWorkflow("Buy", Map.of("part", "12"))).hasMessageContaining("agent_channel Desk");
        assertThat(mock.received()).isEmpty();
    }
}
