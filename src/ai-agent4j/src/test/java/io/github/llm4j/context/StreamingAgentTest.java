package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHandle;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * The agent loop on a streamed model call: assembly, deltas to listeners, and a steer or cancel
 * that closes the call.
 */
class StreamingAgentTest {

    /** A model whose calls are streams. */
    static final class StreamingModel implements LLMClient {
        final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final IntFunction<Stream<LLMResponse>> script;

        StreamingModel(IntFunction<Stream<LLMResponse>> script) {
            this.script = script;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            throw new AssertionError("a streaming agent must not make a whole call");
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            int n;
            synchronized (requests) {
                n = requests.size();
                requests.add(request);
            }
            return script.apply(n);
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    static LLMResponse fin(String reason, Map<String, Object> providerData) {
        return Providers.finalChunk(reason, 10, 5, "m", providerData);
    }

    static Stream<LLMResponse> chunks(LLMResponse... chunks) {
        return Stream.of(chunks);
    }

    static LLMResponse toolStart(String id, String name) {
        return Providers.toolCallChunk(new ToolCallDelta(0, id, name, ""), "m");
    }

    static LLMResponse toolArgs(String fragment) {
        return Providers.toolCallChunk(new ToolCallDelta(0, null, null, fragment), "m");
    }

    /**
     * Yields {@code first}, then blocks (a silent model) until the stream is closed, which makes
     * the read fail as a closed connection does.
     */
    static Stream<LLMResponse> thenSilence(CountDownLatch waiting, LLMResponse... first) {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>(List.of(first));
        Object closed = new Object();
        Iterator<LLMResponse> it =
                new Iterator<>() {
                    LLMResponse next;

                    @Override
                    public boolean hasNext() {
                        if (next != null) return true;
                        try {
                            if (queue.isEmpty()) waiting.countDown();
                            Object o = queue.take();
                            if (o == closed)
                                throw new UncheckedIOException(new IOException("stream closed"));
                            next = (LLMResponse) o;
                            return true;
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }

                    @Override
                    public LLMResponse next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        LLMResponse r = next;
                        next = null;
                        return r;
                    }
                };
        return StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(it, 0), false)
                .onClose(() -> queue.add(closed));
    }

    static ReActAgent.Builder agent(StreamingModel model) {
        return ReActAgent.builder()
                .llmClient(model)
                .addTool(ReActAgentCompactionNativeTest.search(10))
                .streaming(true);
    }

    @Test
    void aStreamedTurnIsPutBackTogetherAndItsToolRuns() {
        Map<String, Object> raw =
                Map.of("anthropic.content", List.of(Map.of("type", "thinking", "signature", "s")));
        StreamingModel model =
                new StreamingModel(
                        n ->
                                n == 0
                                        ? chunks(
                                                Providers.textChunk("Let me ", "m"),
                                                Providers.textChunk("check.", "m"),
                                                toolStart("c0", "search"),
                                                toolArgs("{\"q\":"),
                                                toolArgs("\"cats\"}"),
                                                fin("tool_use", raw))
                                        : chunks(
                                                Providers.textChunk("done", "m"),
                                                fin("end_turn", Map.of())));
        List<String> heard = Collections.synchronizedList(new ArrayList<>());
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {
                        heard.add("thought:" + t);
                    }

                    public void onAction(String n, String i) {
                        heard.add("action:" + n + i);
                    }

                    public void onObservation(String o) {}

                    @Override
                    public void onTextDelta(String p) {
                        heard.add("text:" + p);
                    }

                    @Override
                    public void onToolCallStarted(String n) {
                        heard.add("start:" + n);
                    }
                };
        ScriptedModel.FixedTool tool = ReActAgentCompactionNativeTest.search(10);
        StreamingModel m = model;

        AgentResult r =
                ReActAgent.builder()
                        .llmClient(m)
                        .addTool(tool)
                        .streaming(true)
                        .addListener(listener)
                        .build()
                        .run("Q?");

        assertThat(r.isCompleted()).isTrue();
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(tool.seen).containsExactly(Map.of("q", "cats"));
        assertThat(heard)
                .containsSubsequence(
                        "text:Let me ",
                        "text:check.",
                        "start:search",
                        "thought:Let me check.",
                        "text:done");
        // the provider data of the streamed turn goes back with the tool result, as for a whole
        // call
        Message assistant =
                m.requests.get(1).getMessages().stream()
                        .filter(x -> !x.getToolCalls().isEmpty())
                        .findFirst()
                        .orElseThrow();
        assertThat(assistant.getProviderData()).isEqualTo(raw);
        assertThat(m.requests.get(0).isReasoning()).isFalse();
    }

    @Test
    void reasoningIsAskedForOnlyWhenAskedAndReachesTheListener() {
        StreamingModel model =
                new StreamingModel(
                        n ->
                                chunks(
                                        Providers.reasoningChunk("thinking about ", "m"),
                                                Providers.reasoningChunk("cats", "m"),
                                        Providers.textChunk("done", "m"),
                                                fin("end_turn", Map.of())));
        List<String> reasoning = new ArrayList<>();
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onReasoningDelta(String p) {
                        reasoning.add(p);
                    }
                };

        AgentResult r = agent(model).reasoning(true).addListener(listener).build().run("Q?");

        assertThat(r.getFinalAnswer()).isEqualTo("done"); // the reasoning is not part of the answer
        assertThat(reasoning).containsExactly("thinking about ", "cats");
        assertThat(model.requests.get(0).isReasoning()).isTrue();
    }

    @Test
    void aSteerClosesACallThatIsStillGoingAndTheModelHearsItNext() throws Exception {
        RunHandle handle = new RunHandle();
        CountDownLatch waiting = new CountDownLatch(1);
        StreamingModel model =
                new StreamingModel(
                        n ->
                                n == 0
                                        ? thenSilence(
                                                waiting,
                                                Providers.textChunk(
                                                        "I will start by deleting", "m"))
                                        : chunks(
                                                Providers.textChunk("ok, not deleting", "m"),
                                                fin("end_turn", Map.of())));
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = agent(model).build().run("Clean up", handle));
        runner.start();

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        handle.steer("do not delete anything");
        runner.join(5000);

        assertThat(out[0].getFinalAnswer()).isEqualTo("ok, not deleting");
        List<Message> second = model.requests.get(1).getMessages();
        assertThat(second.get(second.size() - 2).getContent())
                .isEqualTo("I will start by deleting"); // what it had said stays
        assertThat(second.get(second.size() - 1).getContent()).contains("do not delete anything");
        assertThat(second.get(second.size() - 1).getRole()).isEqualTo(Message.Role.USER);
    }

    @Test
    void aCancelClosesACallThatIsStillGoingAndEndsTheRun() throws Exception {
        RunHandle handle = new RunHandle();
        CountDownLatch waiting = new CountDownLatch(1);
        StreamingModel model = new StreamingModel(n -> thenSilence(waiting));
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = agent(model).build().run("Q?", handle));
        runner.start();

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        runner.join(5000);

        assertThat(out[0].isCancelled()).isTrue();
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void aToolCallWhoseArgumentsAreCutOffIsNotRunAndTheModelIsToldToWriteItAgain() {
        StreamingModel model =
                new StreamingModel(
                        n ->
                                n == 0
                                        ? chunks(
                                                toolStart("c0", "search"),
                                                toolArgs("{\"q\":\"ca"),
                                                fin("max_tokens", Map.of()))
                                        : chunks(
                                                Providers.textChunk("done", "m"),
                                                fin("end_turn", Map.of())));
        ScriptedModel.FixedTool tool = ReActAgentCompactionNativeTest.search(10);

        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(tool)
                        .streaming(true)
                        .build()
                        .run("Q?");

        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(tool.seen).isEmpty();
        List<Message> second = model.requests.get(1).getMessages();
        assertThat(second.get(second.size() - 1).getContent())
                .contains("search")
                .contains("Call it again");
    }
}
