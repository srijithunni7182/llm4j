package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class A2AHandlerTest {

    private final FakeTaskRunner runner = new FakeTaskRunner();
    private final A2AHandler handler = new A2AHandler(scope -> runner);
    private static final CallerIdentity ALICE = new CallerIdentity("alice");
    private static final CallerIdentity BOB = new CallerIdentity("bob");

    private static A2AMessage msg(String id) {
        return A2AMessage.user(id, List.of(new Part.Text("hi")));
    }

    private static A2ARequest.Send send(A2AMessage m, boolean immediately) {
        return new A2ARequest.Send(m, immediately, null, null);
    }

    private static <T> T done(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(0, java.util.concurrent.TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("not completed: " + e);
        }
    }

    private RemoteTask task(CallerIdentity who, A2ARequest request) {
        return ((A2AResponse.TaskResponse) done(handler.handle(who, request))).task();
    }

    // ---- identity ----

    @Test
    void noIdentityIsRefusedAndNothingReachesTheRunner() {
        assertThatThrownBy(() -> done(handler.handle(null, send(msg("m"), true)))).isInstanceOf(A2AException.Refused.class);
        assertThat(runner.calls).isEmpty();
        assertThatThrownBy(() -> new CallerIdentity(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHandlerStartsNothingByItself() {
        assertThat(runner.calls).isEmpty();
    }

    // ---- send ----

    @Test
    void aSendThatAsksNotToWaitAnswersAtOnceWithTheNewTask() {
        var t = task(ALICE, send(msg("m-1"), true));
        assertThat(t.id()).startsWith("task-");
        assertThat(runner.calls).containsExactly("start");
    }

    @Test
    void aSendThatMustWaitReturnsAPendingFutureAndNeverBlocks() {
        var stage = handler.handle(ALICE, send(msg("m-1"), false)).toCompletableFuture();
        assertThat(stage).isNotDone();
        var id = runner.tasks.keySet().iterator().next();
        runner.settled.get(id).complete(new TaskRunner.OwnedTask("alice", RemoteTask.of(id, "c", TaskState.COMPLETED), java.time.Instant.now()));
        assertThat(stage).isDone();
        assertThat(((A2AResponse.TaskResponse) stage.join()).task().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void aMessageNamingAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> done(handler.handle(ALICE, send(msg("m").forTask("nope", null), true)))).isInstanceOf(A2AException.TaskNotFound.class);
    }

    @Test
    void aClientSuppliedTaskIdForANewTaskIsNotAccepted() {
        assertThatThrownBy(() -> done(handler.handle(ALICE, send(msg("m").forTask("my-own-id", "ctx-x"), true)))).isInstanceOf(A2AException.TaskNotFound.class);
        assertThat(runner.calls).doesNotContain("start");
    }

    @Test
    void aMismatchingContextAndTaskIsRejected() {
        runner.put("alice", "t-1", "ctx-1", TaskState.INPUT_REQUIRED);
        assertThatThrownBy(() -> done(handler.handle(ALICE, send(msg("m").forTask("t-1", "ctx-other"), true)))).isInstanceOf(A2AException.InvalidParams.class);
        assertThat(runner.calls).doesNotContain("answer");
    }

    @Test
    void theContextIsInferredFromTheTask() {
        runner.put("alice", "t-1", "ctx-1", TaskState.INPUT_REQUIRED);
        var t = task(ALICE, send(msg("m").forTask("t-1", null), true));
        assertThat(t.contextId()).isEqualTo("ctx-1");
        assertThat(runner.calls).contains("answer");
    }

    @Test
    void aTerminalTaskTakesNoMoreMessages() {
        runner.put("alice", "t-1", "ctx-1", TaskState.COMPLETED);
        assertThatThrownBy(() -> done(handler.handle(ALICE, send(msg("m").forTask("t-1", null), true)))).isInstanceOf(A2AException.UnsupportedOperation.class);
        assertThat(runner.calls).doesNotContain("answer");
    }

    @Test
    void aContextTheRunnerCannotHonourIsRejectedAndNeverReplaced() {
        runner.rejectContext = "mine";
        var m = new A2AMessage("m", A2AMessage.Role.USER, List.of(new Part.Text("x")), "mine", null, List.of());
        assertThatThrownBy(() -> done(handler.handle(ALICE, send(m, true)))).isInstanceOf(A2AException.InvalidParams.class);
    }

    // ---- get, cancel ----

    @Test
    void getHonoursHistoryLength() {
        var history = new ArrayList<A2AMessage>();
        for (int i = 0; i < 5; i++) history.add(msg("h" + i));
        runner.tasks.put("t-1", new TaskRunner.OwnedTask("alice", new RemoteTask("t-1", "c", TaskState.WORKING, null, List.of(), history), java.time.Instant.now()));
        assertThat(task(ALICE, new A2ARequest.GetTask("t-1", 2)).history()).extracting(A2AMessage::messageId).containsExactly("h3", "h4");
        assertThat(task(ALICE, new A2ARequest.GetTask("t-1", 0)).history()).isEmpty();
        assertThat(task(ALICE, new A2ARequest.GetTask("t-1", null)).history()).isEmpty();
    }

    @Test
    void cancelTwiceIsTheSameAndAFinishedTaskIsNotCancelable() {
        runner.put("alice", "t-1", "c", TaskState.WORKING);
        assertThat(task(ALICE, new A2ARequest.CancelTask("t-1")).state()).isEqualTo(TaskState.CANCELED);
        assertThat(task(ALICE, new A2ARequest.CancelTask("t-1")).state()).isEqualTo(TaskState.CANCELED);
        assertThat(runner.calls.stream().filter(c -> c.equals("cancel"))).hasSize(1);
        runner.put("alice", "t-2", "c", TaskState.COMPLETED);
        assertThatThrownBy(() -> done(handler.handle(ALICE, new A2ARequest.CancelTask("t-2")))).isInstanceOf(A2AException.TaskNotCancelable.class);
    }

    // ---- scopes ----

    @Test
    void aForeignTaskLooksExactlyLikeAMissingOne() {
        runner.put("alice", "t-1", "c", TaskState.WORKING);
        Throwable foreign = catchThrowable(() -> done(handler.handle(BOB, new A2ARequest.GetTask("t-1", null))));
        Throwable missing = catchThrowable(() -> done(handler.handle(BOB, new A2ARequest.GetTask("t-404", null))));
        assertThat(foreign).isInstanceOf(A2AException.TaskNotFound.class);
        assertThat(foreign.getMessage().replace("t-1", "X")).isEqualTo(missing.getMessage().replace("t-404", "X"));
        assertThatThrownBy(() -> done(handler.handle(BOB, new A2ARequest.CancelTask("t-1")))).isInstanceOf(A2AException.TaskNotFound.class);
        assertThatThrownBy(() -> done(handler.handle(BOB, send(msg("m").forTask("t-1", null), true)))).isInstanceOf(A2AException.TaskNotFound.class);
        assertThat(runner.tasks.get("t-1").task().state()).isEqualTo(TaskState.WORKING);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    @Test
    void theHandlerFiltersByScopeEvenIfTheRunnerLeaks() {
        runner.put("alice", "t-1", "c", TaskState.WORKING);
        runner.put("bob", "t-2", "c", TaskState.WORKING);
        runner.leaky = true;
        var page = (A2AResponse.TaskPage) done(handler.handle(ALICE, listAll()));
        assertThat(page.tasks()).extracting(RemoteTask::id).containsExactly("t-1");
        assertThatThrownBy(() -> done(handler.handle(ALICE, new A2ARequest.GetTask("t-2", null)))).isInstanceOf(A2AException.TaskNotFound.class);
    }

    @Test
    void oneRunnerIsResolvedPerScopeAndARequestNeverReachesAnotherScopesRunner() {
        Map<String, FakeTaskRunner> byScope = new HashMap<>();
        byScope.put("alice", new FakeTaskRunner());
        byScope.put("bob", new FakeTaskRunner());
        var h = new A2AHandler(scope -> byScope.get(scope));
        done(h.handle(ALICE, send(msg("m"), true)));
        assertThat(byScope.get("alice").calls).containsExactly("start");
        assertThat(byScope.get("bob").calls).isEmpty();
        done(h.handle(BOB, send(msg("m2"), true)));
        assertThat(byScope.get("bob").calls).containsExactly("start");
        assertThat(byScope.get("alice").calls).containsExactly("start");
    }

    @Test
    void aTenantIsPassedThroughAndNeverInterpreted() {
        var request = new A2ARequest.Send(msg("m"), true, null, "acme");
        done(handler.handle(ALICE, request));
        assertThat(request.tenant()).isEqualTo("acme");
        assertThat(runner.calls).containsExactly("start");
    }

    // ---- list ----

    private static A2ARequest.ListTasks listAll() {
        return new A2ARequest.ListTasks(null, null, null, null, null, null, false);
    }

    @Test
    void listIsNewestFirstPagedAndTheTokenIsAlwaysPresent() {
        for (int i = 1; i <= 5; i++) runner.put("alice", "t-" + i, "c", TaskState.WORKING);
        var p1 = (A2AResponse.TaskPage) done(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, 2, null, null, null, false)));
        assertThat(p1.tasks()).extracting(RemoteTask::id).containsExactly("t-5", "t-4");
        assertThat(p1.nextPageToken()).isNotEmpty();
        assertThat(p1.totalSize()).isEqualTo(5);
        var p3 = (A2AResponse.TaskPage) done(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, 2, "4", null, null, false)));
        assertThat(p3.tasks()).extracting(RemoteTask::id).containsExactly("t-1");
        assertThat(p3.nextPageToken()).isEmpty();
    }

    @Test
    void pageSizeIsBetweenOneAndAHundredWithADefaultOfFifty() {
        assertThatThrownBy(() -> done(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, 0, null, null, null, false)))).isInstanceOf(A2AException.InvalidParams.class);
        assertThatThrownBy(() -> done(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, 101, null, null, null, false)))).isInstanceOf(A2AException.InvalidParams.class);
        assertThat(((A2AResponse.TaskPage) done(handler.handle(ALICE, listAll()))).pageSize()).isEqualTo(50);
        assertThatThrownBy(() -> done(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, 5, "not-a-number", null, null, false)))).isInstanceOf(A2AException.InvalidParams.class);
    }

    @Test
    void listFiltersByContextAndStatusAndOmitsArtifactsUnlessAsked() {
        runner.tasks.put("t-1", new TaskRunner.OwnedTask("alice", new RemoteTask("t-1", "c1", TaskState.COMPLETED, null, List.of(new Artifact("a", "n", List.of(new Part.Text("x")))), List.of()), java.time.Instant.ofEpochSecond(1)));
        runner.put("alice", "t-2", "c2", TaskState.WORKING);
        var byContext = (A2AResponse.TaskPage) done(handler.handle(ALICE, new A2ARequest.ListTasks("c1", null, null, null, null, null, false)));
        assertThat(byContext.tasks()).extracting(RemoteTask::id).containsExactly("t-1");
        assertThat(byContext.tasks().get(0).artifacts()).isEmpty();
        var withArtifacts = (A2AResponse.TaskPage) done(handler.handle(ALICE, new A2ARequest.ListTasks("c1", null, null, null, null, null, true)));
        assertThat(withArtifacts.tasks().get(0).artifacts()).hasSize(1);
        var byState = (A2AResponse.TaskPage) done(handler.handle(ALICE, new A2ARequest.ListTasks(null, TaskState.WORKING, null, null, null, null, false)));
        assertThat(byState.tasks()).extracting(RemoteTask::id).containsExactly("t-2");
    }
}
