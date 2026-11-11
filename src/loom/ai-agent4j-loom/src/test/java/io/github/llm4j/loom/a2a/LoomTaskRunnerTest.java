package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.Part;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.TaskRunner.OwnedTask;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.a2a.TaskStore;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.LoomTaskRunner;
import io.github.llm4j.loom.host.Persistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 32d, R22 to R24: an exposed workflow served as tasks. A message becomes the workflow's
 * parameters (and only in the ways R23 allows), a task is a durable run owned by one scope, an
 * {@code ask} to a channel whose audience is the caller pauses the task as {@code INPUT_REQUIRED},
 * and what the caller sees of a failure is never the workflow's own error text.
 */
class LoomTaskRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private static final String SOURCE = """
            agent_channel CallerDesk { audience: caller max_questions: 2 }
            expose Totals {
                workflow: Total
                description: "Totals a receipt."
                agent_channel: CallerDesk
                text_input: receipt
                max_input: 1k
            }
            workflow Total(receipt, currency_hint) {
                return "total for {receipt}"
            }
            workflow Pay(receipt) {
                ask CallerDesk "Which currency?" -> reply
                return "paid {receipt} in {reply}"
            }
            workflow Chatty(receipt) {
                note "internal note: the vault code is 1234"
            }
            workflow Breaks(receipt) {
                halt "internal detail: db password is hunter2"
            }
            """;

    private static final class MemoryStore implements TaskStore {
        private final Map<String, OwnedTask> tasks = new ConcurrentHashMap<>();

        @Override
        public void save(OwnedTask task) {
            tasks.put(task.task().id(), task);
        }

        @Override
        public Optional<OwnedTask> find(String scope, String taskId) {
            return Optional.ofNullable(tasks.get(taskId)).filter(t -> t.scope().equals(scope));
        }

        @Override
        public List<OwnedTask> list(String scope) {
            return new ArrayList<>(tasks.values().stream().filter(t -> t.scope().equals(scope)).toList());
        }
    }

    @TempDir Path dir;

    private LoomHost host;
    private MemoryStore store;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);

    /** A runner for the expose of the given workflow; the script's first expose is Total. */
    private LoomTaskRunner runnerFor(String workflow, String textInput, String maxInput) throws Exception {
        if (host != null) host.close();
        String source =
                SOURCE.replace("workflow: Total", "workflow: " + workflow)
                        .replace("text_input: receipt", textInput == null ? "" : "text_input: " + textInput)
                        .replace("max_input: 1k", maxInput == null ? "" : "max_input: " + maxInput);
        Path script = dir.resolve("main.loom");
        Files.writeString(script, source);
        var loaded = new LoomLoader().load(script.toString());
        store = new MemoryStore();
        LoomTaskRunner runner = new LoomTaskRunner(loaded.getExposes().get(0), loaded, store, clock);
        host =
                LoomHost.builder(loaded, new ToolRegistry(), model -> null)
                        .persistence(Persistence.file(dir.resolve("store")))
                        .scriptRef(script.toAbsolutePath().toString())
                        .baseDir(dir)
                        .customizer(runner.wiring())
                        .build();
        return runner.attach(host);
    }

    @BeforeEach
    void noHostYet() {
        host = null;
    }

    @AfterEach
    void closeHost() throws Exception {
        if (host != null) {
            // a run still writing its record would race the removal of the temporary folder
            long until = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < until && host.runs().stream().anyMatch(r -> r.status() == io.github.llm4j.loom.host.RunRecord.Status.RUNNING)) Thread.sleep(10);
            host.close();
        }
    }

    private static A2AMessage text(String text) {
        return A2AMessage.user("m-1", List.of(new Part.Text(text)));
    }

    private static A2AMessage json(String body) throws Exception {
        return A2AMessage.user("m-1", List.of(new Part.Data(JSON.readTree(body))));
    }

    private OwnedTask settle(LoomTaskRunner runner, OwnedTask started) throws Exception {
        OwnedTask settled = runner.settled(started.scope(), started.task().id()).toCompletableFuture().get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        // the host writes the run's record just after the run's end is seen: wait for it, so nothing writes while the folder is removed
        long until = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < until) {
            var record = host.run(started.task().id());
            if (record.isPresent() && record.get().status() != io.github.llm4j.loom.host.RunRecord.Status.RUNNING) break;
            Thread.sleep(10);
        }
        return settled;
    }

    @Test
    void dataBindsByNameUnknownKeysAreIgnoredAndTheTaskCompletesWithTheResult() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");

        OwnedTask started = runner.start("alice", json("{\"receipt\":\"lunch\",\"currency_hint\":\"EUR\",\"ignored\":1}"), null);
        OwnedTask done = settle(runner, started);

        assertThat(done.task().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(done.task().artifacts()).singleElement().satisfies(a -> assertThat(a.parts()).containsExactly(new Part.Text("total for lunch")));
        assertThat(runner.get("alice", started.task().id())).isPresent();
    }

    @Test
    void textGoesToTheTextInputParameterAndTheOtherParametersMustBeGivenAsData() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);
        assertThat(settle(runner, started).task().state()).isEqualTo(TaskState.INPUT_REQUIRED);

        LoomTaskRunner total = runnerFor("Total", "receipt", "1k");
        assertThatThrownBy(() -> total.start("alice", text("lunch"), null))
                .isInstanceOf(A2AException.InvalidParams.class)
                .hasMessageContaining("currency_hint");
    }

    @Test
    void aTextMessageWithNoTextInputIsRefused() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", null, "1k");

        assertThatThrownBy(() -> runner.start("alice", text("lunch"), null))
                .isInstanceOf(A2AException.InvalidParams.class)
                .hasMessageContaining("text_input");
        assertThat(host.runs()).as("nothing was started").isEmpty();
    }

    @Test
    void aMissingParameterIsRefusedAndTheMessageListsWhatTheWorkflowTakes() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");

        assertThatThrownBy(() -> runner.start("alice", json("{\"currency_hint\":\"EUR\"}"), null))
                .isInstanceOf(A2AException.InvalidParams.class)
                .hasMessageContaining("receipt")
                .hasMessageContaining("currency_hint");
        assertThat(host.runs()).isEmpty();
    }

    @Test
    void filesAreRefusedBeforeAnyWorkStarts() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        A2AMessage withFile = A2AMessage.user("m-1", List.of(new Part.Text("lunch"), Part.FileOffered.inline(new byte[] {1, 2}, "x.png", "image/png")));

        assertThatThrownBy(() -> runner.start("alice", withFile, null)).isInstanceOf(A2AException.ContentTypeNotSupported.class);
        assertThat(host.runs()).isEmpty();
    }

    @Test
    void aMessageOverMaxInputIsRefusedBeforeAnyWorkStarts() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");

        assertThatThrownBy(() -> runner.start("alice", text("x".repeat(1025)), null))
                .isInstanceOf(A2AException.InvalidParams.class)
                .hasMessageContaining("max_input");
        assertThat(host.runs()).isEmpty();
    }

    @Test
    void aTaskIsVisibleOnlyToTheScopeThatMadeIt() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);
        settle(runner, started);

        assertThat(runner.get("bob", started.task().id())).isEmpty();
        assertThat(runner.list("bob")).isEmpty();
        assertThat(runner.list("alice")).hasSize(1);
        assertThatThrownBy(() -> runner.cancel("bob", started.task().id())).isInstanceOf(A2AException.TaskNotFound.class);
    }

    @Test
    void anAskToACallerChannelPausesTheTaskAsInputRequiredAndTheAnswerCompletesIt() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);

        OwnedTask waiting = settle(runner, started);
        assertThat(waiting.task().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(waiting.task().statusMessage().text()).isEqualTo("Which currency?");

        OwnedTask answered = runner.answer("alice", started.task().id(), text("EUR"));
        assertThat(answered.task().state()).isEqualTo(TaskState.WORKING);
        OwnedTask done = settle(runner, answered);

        assertThat(done.task().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(done.task().artifacts().get(0).parts()).containsExactly(new Part.Text("paid lunch in EUR"));
    }

    @Test
    void anAnswerToATaskThatIsNotWaitingIsRefused() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");
        OwnedTask started = runner.start("alice", json("{\"receipt\":\"lunch\",\"currency_hint\":\"EUR\"}"), null);
        settle(runner, started);

        assertThatThrownBy(() -> runner.answer("alice", started.task().id(), text("EUR"))).isInstanceOf(A2AException.class);
    }

    @Test
    void cancellingAWaitingTaskEndsItAsCanceled() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);
        settle(runner, started);

        OwnedTask canceled = runner.cancel("alice", started.task().id());

        assertThat(canceled.task().state()).isEqualTo(TaskState.CANCELED);
        assertThat(runner.get("alice", started.task().id()).orElseThrow().task().state()).isEqualTo(TaskState.CANCELED);
    }

    @Test
    void aFailedWorkflowShowsTheCallerNothingOfItsOwnErrorText() throws Exception {
        LoomTaskRunner runner = runnerFor("Breaks", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);

        OwnedTask done = settle(runner, started);

        assertThat(done.task().state()).isEqualTo(TaskState.FAILED);
        assertThat(String.valueOf(done.task())).doesNotContain("hunter2").doesNotContain("internal detail");
    }

    @Test
    void theContextTheCallerProposedIsKeptAndOneIsMadeWhenNoneWasProposed() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");

        OwnedTask given = runner.start("alice", text("lunch"), "ctx-7");
        OwnedTask made = runner.start("alice", text("dinner"), null);

        assertThat(given.task().contextId()).isEqualTo("ctx-7");
        assertThat(made.task().contextId()).isNotBlank();
    }

    @Test
    void whateverMakesTheRunFailTheCallerSeesOneFixedSentence() throws Exception {
        LoomTaskRunner runner = runnerFor("Breaks", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);

        OwnedTask done = settle(runner, started);

        assertThat(done.task().state()).isEqualTo(TaskState.FAILED);
        assertThat(done.task().statusMessage().text()).isEqualTo("The workflow did not finish.");
    }

    @Test
    void onlyWhatTheWorkflowReturnsIsTheResultNotItsNotes() throws Exception {
        LoomTaskRunner runner = runnerFor("Chatty", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);

        OwnedTask done = settle(runner, started);

        assertThat(done.task().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(done.task().artifacts()).isEmpty();
    }

    @Test
    void aFinishedTaskCannotBeCancelled() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");
        OwnedTask started = runner.start("alice", json("{\"receipt\":\"lunch\",\"currency_hint\":\"EUR\"}"), null);
        settle(runner, started);

        assertThatThrownBy(() -> runner.cancel("alice", started.task().id())).isInstanceOf(A2AException.TaskNotCancelable.class);
    }

    @Test
    void aDataPartThatIsNotAnObjectIsRefused() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");

        assertThatThrownBy(() -> runner.start("alice", json("[\"lunch\"]"), null)).isInstanceOf(A2AException.InvalidParams.class).hasMessageContaining("JSON object");
        assertThat(host.runs()).isEmpty();
    }

    @Test
    void whatHappensToATaskIsReportedWithoutTheCallerOrAnyText() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        List<LoomTaskRunner.ServedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        runner.onEvent(events::add);

        OwnedTask started = runner.start("alice", text("lunch SECRET-CANARY"), null);
        settle(runner, started);
        runner.answer("alice", started.task().id(), text("EUR SECRET-CANARY"));
        settle(runner, store.find("alice", started.task().id()).orElseThrow());

        assertThat(events.stream().map(LoomTaskRunner.ServedEvent::kind))
                .containsSubsequence("received", "state", "answered", "state");
        assertThat(events.stream().map(LoomTaskRunner.ServedEvent::state)).contains("INPUT_REQUIRED", "COMPLETED");
        assertThat(String.valueOf(events)).doesNotContain("alice").doesNotContain("SECRET-CANARY").doesNotContain("lunch");
    }

    @Test
    void aCancelIsReported() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        List<LoomTaskRunner.ServedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        runner.onEvent(events::add);
        OwnedTask started = runner.start("alice", text("lunch"), null);
        settle(runner, started);

        runner.cancel("alice", started.task().id());

        assertThat(events.stream().map(LoomTaskRunner.ServedEvent::kind)).contains("cancelled");
    }

    @Test
    void theRunsOfAServedWorkflowSayInTheirTraceThatOutsidersStartedThem() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");
        List<Object> served = new java.util.concurrent.CopyOnWriteArrayList<>();
        var wiring = runner.wiring();
        // a second customizer reads the start event of the run the runner's own wiring marked
        io.github.llm4j.loom.execution.HarnessExecutor probe = host.newExecutor("probe-run");
        probe.addTraceListener(e -> {
            if (e.type().equals(io.github.llm4j.loom.execution.TraceEvent.RUN_START)) served.add(e.data().get("served"));
        });
        probe.executeWorkflow("Total", new java.util.HashMap<>(Map.of("receipt", "x", "currency_hint", "EUR")));

        assertThat(served).containsExactly("true");
    }

    // ---- the limits and the errors around them

    @Test
    void maxInputUnderstandsBytesKilobytesAndMegabytesAndDefaultsToSixtyFourK() throws Exception {
        assertThatThrownBy(() -> runnerFor("Pay", "receipt", "500").start("alice", text("x".repeat(501)), null)).isInstanceOf(A2AException.InvalidParams.class);
        assertThat(runnerFor("Pay", "receipt", "2kb").start("alice", text("x".repeat(1500)), null).task().state()).isIn(TaskState.WORKING, TaskState.INPUT_REQUIRED);
        assertThat(runnerFor("Pay", "receipt", "1mb").start("alice", text("x".repeat(2000)), null).task().id()).isNotBlank();
        assertThat(runnerFor("Pay", "receipt", null).start("alice", text("x".repeat(5000)), null).task().id()).isNotBlank();
        assertThatThrownBy(() -> runnerFor("Pay", "receipt", null).start("alice", text("x".repeat(70_000)), null)).isInstanceOf(A2AException.InvalidParams.class);
    }

    @Test
    void aRunnerThatWasNeverAttachedToAHostSaysSo() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(script, SOURCE);
        var loaded = new LoomLoader().load(script.toString());
        var runner = new LoomTaskRunner(loaded.getExposes().get(0), loaded, new MemoryStore(), clock);

        assertThatThrownBy(() -> runner.start("alice", text("lunch"), null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("attach");
    }

    @Test
    void anAnswerNeedsTextAndAnExistingWaitingTaskAndAReasonableSize() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        OwnedTask started = runner.start("alice", text("lunch"), null);
        settle(runner, started);

        assertThatThrownBy(() -> runner.answer("alice", "no-such-task", text("EUR"))).isInstanceOf(A2AException.TaskNotFound.class);
        assertThatThrownBy(() -> runner.answer("bob", started.task().id(), text("EUR"))).isInstanceOf(A2AException.TaskNotFound.class);
        assertThatThrownBy(() -> runner.answer("alice", started.task().id(), A2AMessage.user("m", List.of(new Part.Data(JSON.readTree("{}")))))).isInstanceOf(A2AException.InvalidParams.class);
        assertThatThrownBy(() -> runner.answer("alice", started.task().id(), text("x".repeat(2000)))).isInstanceOf(A2AException.InvalidParams.class).hasMessageContaining("max_input");
        assertThat(runner.get("alice", started.task().id()).orElseThrow().task().state()).as("a refused answer leaves it waiting").isEqualTo(TaskState.INPUT_REQUIRED);
    }

    @Test
    void cancelAndSettledOnATaskThatDoesNotExistAreNotFoundAndSettledOnAWaitingTaskAnswersAtOnce() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");

        assertThatThrownBy(() -> runner.cancel("alice", "nope")).isInstanceOf(A2AException.TaskNotFound.class);
        assertThatThrownBy(() -> runner.settled("alice", "nope")).isInstanceOf(A2AException.TaskNotFound.class);

        OwnedTask started = runner.start("alice", text("lunch"), null);
        OwnedTask waiting = settle(runner, started);
        assertThat(runner.settled("alice", started.task().id()).toCompletableFuture().isDone()).as("already waiting on the caller").isTrue();
        assertThat(waiting.task().state()).isEqualTo(TaskState.INPUT_REQUIRED);
    }

    // ---- what a data part may hold

    @Test
    void numbersAndBooleansBindAsTheirJsonTextAndANullCountsAsMissing() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");

        OwnedTask started = runner.start("alice", json("{\"receipt\":12.5,\"currency_hint\":true}"), null);
        OwnedTask done = settle(runner, started);
        assertThat(done.task().artifacts().get(0).parts()).containsExactly(new Part.Text("total for 12.5"));

        assertThatThrownBy(() -> runner.start("alice", json("{\"receipt\":null,\"currency_hint\":\"EUR\"}"), null))
                .isInstanceOf(A2AException.InvalidParams.class)
                .hasMessageContaining("receipt");
    }

    @Test
    void aParameterGivenAsTextAndAsDataIsRefused() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");
        A2AMessage both = A2AMessage.user("m", List.of(new Part.Text("lunch"), new Part.Data(JSON.readTree("{\"receipt\":\"dinner\",\"currency_hint\":\"EUR\"}"))));

        assertThatThrownBy(() -> runner.start("alice", both, null)).isInstanceOf(A2AException.InvalidParams.class).hasMessageContaining("twice");
        assertThat(host.runs()).isEmpty();
    }

    @Test
    void severalTextPartsAreJoinedWithABlankLine() throws Exception {
        LoomTaskRunner runner = runnerFor("Pay", "receipt", "1k");
        A2AMessage two = A2AMessage.user("m", List.of(new Part.Text("one"), new Part.Text("two")));

        OwnedTask started = runner.start("alice", two, null);
        settle(runner, started);
        OwnedTask answered = runner.answer("alice", started.task().id(), text("EUR"));
        OwnedTask done = settle(runner, answered);

        assertThat(done.task().artifacts().get(0).parts()).containsExactly(new Part.Text("paid one\n\ntwo in EUR"));
    }

    @Test
    void aRunTheRunnerDidNotStartIsNotATask() throws Exception {
        LoomTaskRunner runner = runnerFor("Total", "receipt", "1k");

        host.run(io.github.llm4j.loom.host.RunRequest.of("Pay", Map.of("receipt", "lunch")).withRunId("direct-1"));

        assertThat(store.list("alice")).isEmpty();
        assertThat(runner.get("alice", "direct-1")).isEmpty();
    }
}
