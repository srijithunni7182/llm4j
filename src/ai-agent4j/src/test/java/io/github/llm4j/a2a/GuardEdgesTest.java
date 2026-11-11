package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.TinyMedia;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edges of the guards that a mutation run found nothing watching: what the handler does with a
 * runner that shows another scope's task, the page bounds, what a reply may carry, the claimed type
 * of a received file and what an endpoint check hands back.
 */
class GuardEdgesTest {

    private static final CallerIdentity ALICE = new CallerIdentity("alice");

    private static A2AMessage msg(String id) {
        return A2AMessage.user(id, List.of(new Part.Text("hi")));
    }

    /** A runner that gets the scope wrong in one place, to show the handler checks again. */
    private static final class LyingRunner implements TaskRunner {
        private final String lieAbout;
        private final FakeTaskRunner honest = new FakeTaskRunner();

        LyingRunner(String lieAbout) {
            this.lieAbout = lieAbout;
        }

        private OwnedTask foreign(OwnedTask task) {
            return new OwnedTask("mallory", task.task(), task.updated());
        }

        @Override
        public OwnedTask start(String scope, A2AMessage message, String contextId) {
            OwnedTask task = honest.start(scope, message, contextId);
            return lieAbout.equals("start") ? foreign(task) : task;
        }

        @Override
        public Optional<OwnedTask> get(String scope, String taskId) {
            return honest.get(scope, taskId);
        }

        @Override
        public List<OwnedTask> list(String scope) {
            return honest.list(scope);
        }

        @Override
        public OwnedTask answer(String scope, String taskId, A2AMessage message) {
            return honest.answer(scope, taskId, message);
        }

        @Override
        public OwnedTask cancel(String scope, String taskId) {
            OwnedTask task = honest.cancel(scope, taskId);
            return lieAbout.equals("cancel") ? foreign(task) : task;
        }

        @Override
        public CompletionStage<OwnedTask> settled(String scope, String taskId) {
            if (lieAbout.equals("settled")) {
                return CompletableFuture.completedFuture(
                        new OwnedTask(
                                "mallory",
                                RemoteTask.of(taskId, "c", TaskState.COMPLETED),
                                Instant.now()));
            }
            return honest.settled(scope, taskId);
        }
    }

    private static <T> T result(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ---- the handler checks the runner's scope again

    @Test
    void aRunnerThatStartsATaskInAnotherScopeIsRefused() {
        var handler = new A2AHandler(scope -> new LyingRunner("start"));

        assertThatThrownBy(() -> result(handler.handle(ALICE, new A2ARequest.Send(msg("m"), true, null, null))))
                .hasRootCauseInstanceOf(A2AException.class)
                .hasMessageContaining("another scope");
    }

    @Test
    void aRunnerThatSettlesATaskInAnotherScopeIsRefused() {
        var runner = new LyingRunner("settled");
        var handler = new A2AHandler(scope -> runner);

        assertThatThrownBy(() -> result(handler.handle(ALICE, new A2ARequest.Send(msg("m"), false, null, null))))
                .hasMessageContaining("another scope");
    }

    @Test
    void aRunnerThatCancelsATaskInAnotherScopeIsRefused() throws Exception {
        var runner = new LyingRunner("cancel");
        runner.honest.put("alice", "t-1", "c", TaskState.WORKING);
        var handler = new A2AHandler(scope -> runner);

        assertThatThrownBy(() -> result(handler.handle(ALICE, new A2ARequest.CancelTask("t-1"))))
                .hasMessageContaining("another scope");
    }

    @Test
    void aRunnerThatListsAnotherScopesTaskDoesNotShowItAndTheCountExcludesIt() throws Exception {
        var runner = new FakeTaskRunner();
        runner.leaky = true;
        runner.put("alice", "t-a", "c", TaskState.WORKING);
        runner.put("bob", "t-b", "c", TaskState.WORKING);
        var handler = new A2AHandler(scope -> runner);

        var page = (A2AResponse.TaskPage) result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, null, null, null, null, false)));

        assertThat(page.tasks()).extracting(RemoteTask::id).containsExactly("t-a");
        assertThat(page.totalSize()).isEqualTo(1);
    }

    @Test
    void pageSizesAtTheBoundsAreAcceptedAndOutsideThemRefused() throws Exception {
        var handler = new A2AHandler(scope -> new FakeTaskRunner());

        for (int ok : new int[] {1, A2AHandler.MAX_PAGE_SIZE})
            assertThat(result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, ok, null, null, null, false)))).isInstanceOf(A2AResponse.TaskPage.class);
        for (int bad : new int[] {0, A2AHandler.MAX_PAGE_SIZE + 1})
            assertThatThrownBy(() -> result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, bad, null, null, null, false))))
                    .hasRootCauseInstanceOf(A2AException.InvalidParams.class);
    }

    @Test
    void aPageTokenOfZeroIsTheStartAndANegativeOneIsNotOurs() throws Exception {
        var runner = new FakeTaskRunner();
        runner.put("alice", "t-1", "c", TaskState.WORKING);
        var handler = new A2AHandler(scope -> runner);

        var first = (A2AResponse.TaskPage) result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, null, "0", null, null, false)));
        assertThat(first.tasks()).hasSize(1);
        assertThatThrownBy(() -> result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, null, "-1", null, null, false))))
                .hasRootCauseInstanceOf(A2AException.InvalidParams.class);
    }

    // ---- an exchange guard's reply bound

    @Test
    void aReplyExactlyAtTheLimitIsAcceptedAndOneByteOverIsNot() {
        var guard = new ExchangeGuard(3, Duration.ofSeconds(5), 4, ExchangeGuard.Waiter.direct());
        var context = new ChannelContext("r", "s", "w", ChannelContext.Direction.INBOUND_QUESTION, "Supplier", null, 3, null);
        var question = new ChannelMessage.Question("q", null, null, 1, null);

        assertThat(guard.exchange((c, m) -> new ChannelResponse.Reply("abcd", null), context, question, reply -> Optional.empty()))
                .isEqualTo(new ChannelResponse.Reply("abcd", null));
        assertThatThrownBy(() -> new ExchangeGuard(3, Duration.ofSeconds(5), 4, ExchangeGuard.Waiter.direct())
                        .exchange((c, m) -> new ChannelResponse.Reply("abcde", null), context, question, reply -> Optional.empty()))
                .isInstanceOf(A2AException.ChannelFailure.class);
    }

    @Test
    void aReplyLimitOfOneByteIsAllowedAndZeroIsNot() {
        new ExchangeGuard(1, Duration.ofSeconds(1), 1, ExchangeGuard.Waiter.direct());

        assertThatThrownBy(() -> new ExchangeGuard(1, Duration.ofSeconds(1), 0, ExchangeGuard.Waiter.direct()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- what a remote may send back: its claimed type must match its bytes

    @TempDir Path root;

    @Test
    void aFileThatClaimsAnotherKindThanItsBytesIsRefusedWhateverTheClaim() throws Exception {
        for (String claim : new String[] {"audio/mpeg", "video/mp4", "application/pdf", "image/jpeg"}) {
            var sink = new ReceivedFileSink(root, "run-" + claim.replace('/', '-'), MediaPolicy.defaults(), 4);
            // the bytes are a PDF (for the claims of image, audio and video) or a PNG (for the claim of a PDF)
            byte[] bytes = claim.equals("application/pdf") ? TinyMedia.png() : TinyMedia.pdf();
            try (var out = sink.open("f", claim)) {
                out.write(bytes);
            } catch (java.io.IOException refused) {
                // closing a refused file may throw; the file must still not be recorded
            }
            assertThat(sink.files()).as("claimed " + claim).isEmpty();
        }
    }

    // ---- the address check hands back what it checked

    @Test
    void requireHttpsReturnsTheHostItAccepted() {
        assertThat(EndpointGuard.requireHttps("https://supplier.example.com/card.json", "the card")).isEqualTo("supplier.example.com");
    }

    // ---- the file-type check on a reply, and where the accepted types come from

    private static final AgentCardView.Endpoint ENDPOINT = new AgentCardView.Endpoint("https://supplier.example.com/rpc", "JSONRPC", "1.0", null);
    private static final AgentChannel NO_CHANNEL = (c, m) -> new ChannelResponse.Refuse("not used");

    private static RemoteAgent agent(FakeTransport transport) {
        return RemoteAgent.builder("https://supplier.example.com/.well-known/agent-card.json", transport, NO_CHANNEL).build();
    }

    @Test
    void aReplyWithAFileTheCardDoesNotAcceptIsRefusedAndNothingIsSent() {
        var card = AgentCardView.builder("Supplier", "sells").endpoints(List.of(ENDPOINT)).defaultInputMediaTypes(List.of("text/plain", "image/*")).build();
        var transport = new FakeTransport().card(card).getReplies(RemoteTask.of("t-1", "c-1", TaskState.INPUT_REQUIRED));
        var pdf = A2AMessage.user("m", List.of(Part.FileOffered.inline(new byte[] {1}, "a", "application/pdf")));

        assertThatThrownBy(() -> agent(transport).reply("t-1", pdf)).isInstanceOf(A2AException.Refused.class).hasMessageContaining("application/pdf");
        assertThat(transport.operationNames()).doesNotContain("send");
    }

    @Test
    void whenTheCardStatesNoDefaultTypesTheSkillsTypesDecide() {
        var skill = new AgentCardView.Skill("quote", "Quote", "Prices a part", List.of(), List.of(), List.of("image/png"), List.of());
        var card = AgentCardView.builder("Supplier", "sells").endpoints(List.of(ENDPOINT)).skills(List.of(skill)).build();
        var working = new SendResult.TaskResult(RemoteTask.of("t-1", "c-1", TaskState.WORKING));
        var transport = new FakeTransport().card(card).sendReplies(working);
        var pdf = A2AMessage.user("m", List.of(Part.FileOffered.inline(new byte[] {1}, "a", "application/pdf")));
        var png = A2AMessage.user("m2", List.of(Part.FileOffered.inline(new byte[] {1}, "a", "image/png")));

        assertThatThrownBy(() -> agent(transport).send(pdf)).isInstanceOf(A2AException.Refused.class);
        agent(transport).send(png);
        assertThat(transport.operationNames()).contains("send");
    }

    // ---- the three that a first mutation run left

    @Test
    void aStatusTimestampFilterLeavesOutTasksThatChangedBeforeIt() throws Exception {
        var runner = new FakeTaskRunner();
        runner.put("alice", "t-old", "c", TaskState.WORKING); // updated at second 1
        runner.put("alice", "t-new", "c", TaskState.WORKING); // updated at second 2
        var handler = new A2AHandler(scope -> runner);

        var page = (A2AResponse.TaskPage) result(handler.handle(ALICE, new A2ARequest.ListTasks(null, null, null, null, null, Instant.ofEpochSecond(2), false)));

        assertThat(page.tasks()).extracting(RemoteTask::id).containsExactly("t-new");
    }

    @Test
    void aReplyThatTheRemoteAnswersWithAMessageReadsTheTaskAgain() {
        var waiting = RemoteTask.of("t-1", "c-1", TaskState.INPUT_REQUIRED);
        var next = RemoteTask.of("t-1", "c-1", TaskState.WORKING);
        var card = AgentCardView.builder("Supplier", "sells").endpoints(List.of(ENDPOINT)).build();
        var message = A2AMessage.agent("a-1", List.of(new Part.Text("noted")));
        var transport = new FakeTransport().card(card).getReplies(waiting, next).sendReplies(new SendResult.MessageResult(message));

        var result = agent(transport).reply("t-1", msg("m-9"));

        assertThat(result).isEqualTo(next);
    }

    @Test
    void anInterruptedChannelCallFailsTheExchangeAndKeepsTheInterruptFlag() {
        var guard = new ExchangeGuard(3, Duration.ofSeconds(5), 100, (call, timeout) -> {
            throw new InterruptedException("stop");
        });
        var context = new ChannelContext("r", "s", "w", ChannelContext.Direction.INBOUND_QUESTION, "Supplier", null, 3, null);
        try {
            assertThatThrownBy(() -> guard.exchange((c, m) -> new ChannelResponse.Reply("x", null), context, new ChannelMessage.Question("q", null, null, 1, null), reply -> Optional.empty()))
                    .isInstanceOf(A2AException.ChannelFailure.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).as("the flag is kept").isTrue();
        } finally {
            Thread.interrupted(); // clear it for the next test
        }
    }
}
