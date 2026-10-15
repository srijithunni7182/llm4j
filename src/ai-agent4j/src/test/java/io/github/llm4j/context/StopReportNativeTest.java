package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHandle;
import io.github.llm4j.agent.StopReport;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.provider.Providers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The stop report and the wrap-up in the native tool-calling loop, whole and streamed. */
class StopReportNativeTest {

    @Test
    void aCancelBetweenTheCallsOfOneTurnSaysSoAndKeepsWhatRan() {
        RunHandle handle = new RunHandle();
        ScriptedModel.FixedTool tool = new ScriptedModel.FixedTool("search", n -> {
            handle.cancel("wrong input", "alice");
            return "r";
        });
        ScriptedModel model = ScriptedModel.by(true, true, n -> ScriptedModel.calls("two at once",
                ScriptedModel.call("a", "search", Map.of("q", "1")), ScriptedModel.call("b", "search", Map.of("q", "2"))));

        AgentResult r = ReActAgent.builder().llmClient(model).addTool(tool).build().run("Q?", handle);

        StopReport stop = r.getStop();
        assertThat(stop.phase()).isEqualTo(StopReport.Phase.BETWEEN_TOOLS);
        assertThat(stop.steps()).hasSize(1);
        assertThat(stop.reason()).isEqualTo("wrong input");
        assertThat(stop.mayHaveChangedThings()).isTrue();
        assertThat(tool.seen).hasSize(1);
    }

    @Test
    void aWrapUpMakesOneMoreCallWithToolsSwitchedOffAndTakesItsAnswer() {
        RunHandle handle = new RunHandle();
        ScriptedModel.FixedTool tool = new ScriptedModel.FixedTool("search", n -> {
            handle.wrapUp("deadline", "scheduler");
            return "found something";
        });
        ScriptedModel model = ScriptedModel.by(true, true, n -> n == 0
                ? ScriptedModel.calls("looking", ScriptedModel.call("c0", "search", Map.of("q", "a")))
                : ScriptedModel.answer("here is what I have"));

        AgentResult r = ReActAgent.builder().llmClient(model).addTool(tool).build().run("Q?", handle);

        assertThat(r.isWrappedUp()).isTrue();
        assertThat(r.getFinalAnswer()).isEqualTo("here is what I have");
        assertThat(model.requests).hasSize(2);
        LLMRequest last = model.requests.get(1);
        assertThat(last.getToolChoice()).isEqualTo(LLMRequest.ToolChoice.NONE);
        Message told = last.getMessages().get(last.getMessages().size() - 1);
        assertThat(told.getRole()).isEqualTo(Message.Role.USER);
        assertThat(told.getContent()).contains("Stop using tools now");
        assertThat(r.getStop().reason()).isEqualTo("deadline");
        assertThat(r.getStop().origin()).isEqualTo("scheduler");
        assertThat(r.getStop().steps()).hasSize(1);
    }

    /** A model whose streamed calls come from a script and whose whole calls (the wrap-up) answer {@code wrapUpAnswer}. */
    static final class StreamingThenWhole implements LLMClient {
        final List<LLMRequest> streamed = Collections.synchronizedList(new ArrayList<>());
        final List<LLMRequest> whole = Collections.synchronizedList(new ArrayList<>());
        private final IntFunction<Stream<LLMResponse>> script;
        private final String wrapUpAnswer;

        StreamingThenWhole(IntFunction<Stream<LLMResponse>> script, String wrapUpAnswer) {
            this.script = script;
            this.wrapUpAnswer = wrapUpAnswer;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            whole.add(request);
            return LLMResponse.builder().content(wrapUpAnswer).model("m").build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            int n;
            synchronized (streamed) {
                n = streamed.size();
                streamed.add(request);
            }
            return script.apply(n);
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    @Test
    void aCancelWhileAStreamingCallIsGoingSaysItWasInTheModelCallAndKeepsWhatWasSaid() throws Exception {
        RunHandle handle = new RunHandle();
        CountDownLatch waiting = new CountDownLatch(1);
        StreamingThenWhole model = new StreamingThenWhole(
                n -> StreamingAgentTest.thenSilence(waiting, Providers.textChunk("I will start by deleting", "m")), "unused");
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = ReActAgent.builder().llmClient(model)
                .addTool(ReActAgentCompactionNativeTest.search(10)).streaming(true).build().run("Clean up", handle));
        runner.start();

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel("stop that", "alice");
        runner.join(5000);

        StopReport stop = out[0].getStop();
        assertThat(stop.phase()).isEqualTo(StopReport.Phase.MODEL_CALL);
        assertThat(stop.reason()).isEqualTo("stop that");
        assertThat(model.whole).isEmpty();
    }

    @Test
    void aWrapUpWhileAStreamingCallIsGoingClosesItThenMakesTheLastCall() throws Exception {
        RunHandle handle = new RunHandle();
        CountDownLatch waiting = new CountDownLatch(1);
        StreamingThenWhole model = new StreamingThenWhole(
                n -> StreamingAgentTest.thenSilence(waiting, Providers.textChunk("Working on the summary", "m")), "the summary so far");
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = ReActAgent.builder().llmClient(model)
                .addTool(ReActAgentCompactionNativeTest.search(10)).streaming(true).build().run("Summarise", handle));
        runner.start();

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        handle.wrapUp("time is up", "scheduler");
        runner.join(5000);

        assertThat(out[0].isWrappedUp()).isTrue();
        assertThat(out[0].getFinalAnswer()).isEqualTo("the summary so far");
        assertThat(model.whole).hasSize(1);
        List<Message> sent = model.whole.get(0).getMessages();
        assertThat(sent.get(sent.size() - 2).getContent()).as("what it had said stays").isEqualTo("Working on the summary");
        assertThat(model.whole.get(0).getToolChoice()).isEqualTo(LLMRequest.ToolChoice.NONE);
        assertThat(out[0].getStop().phase()).isEqualTo(StopReport.Phase.MODEL_CALL);
    }
}
