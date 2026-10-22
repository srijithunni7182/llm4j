package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.tools.EchoTool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RunHandleTest {

    private static final String ECHO =
            "Thought: look.\nAction: echo\nAction Input: {\"input\":\"hi\"}\n";
    private static final String DONE = "Thought: done.\nFinal Answer: finished";

    /** Replies from a script, one per call, and records what it was sent. */
    private static LLMClient scripted(
            List<LLMRequest> seen, Runnable beforeReply, String... replies) {
        LLMClient client = Mockito.mock(LLMClient.class);
        AtomicInteger n = new AtomicInteger();
        Mockito.when(client.chat(Mockito.any(LLMRequest.class)))
                .thenAnswer(
                        call -> {
                            seen.add(call.getArgument(0));
                            beforeReply.run();
                            int i = Math.min(n.getAndIncrement(), replies.length - 1);
                            return LLMResponse.builder().content(replies[i]).build();
                        });
        return client;
    }

    private static String sentText(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        request.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    private static ReActAgent agent(LLMClient client, AgentEventListener... listeners) {
        ReActAgent.Builder b = ReActAgent.builder().llmClient(client).addTool(new EchoTool());
        for (AgentEventListener l : listeners) b.addListener(l);
        return b.build();
    }

    @Test
    void steeringReachesTheModelBeforeItsNextCall() {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new ArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        // the user speaks while the first model call is being answered
        LLMClient client =
                scripted(
                        seen,
                        () -> {
                            if (seen.size() == 1) handle.steer("use the other tool");
                        },
                        ECHO,
                        DONE);
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onSteered(String m) {
                        events.add(m);
                    }
                };

        AgentResult result = agent(client, listener).run("do it", handle);

        assertThat(result.isCompleted()).isTrue();
        assertThat(sentText(seen.get(0))).doesNotContain("use the other tool");
        assertThat(sentText(seen.get(1))).contains("Message from the user: use the other tool");
        assertThat(events).containsExactly("use the other tool");
        assertThat(handle.state()).isEqualTo(RunHandle.State.FINISHED);
    }

    @Test
    void cancelEndsTheRunAtTheNextCheckpoint() {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new ArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        LLMClient client = scripted(seen, () -> handle.cancel(), ECHO, ECHO, DONE);
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onCancelled() {
                        events.add("cancelled");
                    }
                };

        AgentResult result = agent(client, listener).run("do it", handle);

        assertThat(result.isCancelled()).isTrue();
        assertThat(result.isCompleted()).isFalse();
        assertThat(seen).hasSize(1); // the first call was already under way; no second one was made
        assertThat(events).containsExactly("cancelled");
        assertThat(handle.state()).isEqualTo(RunHandle.State.CANCELLED);
    }

    @Test
    void pauseHoldsTheRunUntilResumed() throws Exception {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new CopyOnWriteArrayList<>();
        CountDownLatch paused = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        LLMClient client =
                scripted(
                        seen,
                        () -> {
                            if (seen.size() == 1) handle.pause();
                        },
                        ECHO,
                        DONE);
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onPaused() {
                        events.add("paused");
                        paused.countDown();
                    }

                    @Override
                    public void onResumed() {
                        events.add("resumed");
                    }
                };
        ReActAgent agent = agent(client, listener);
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = agent.run("do it", handle));
        runner.start();

        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(handle.state()).isEqualTo(RunHandle.State.PAUSED);
        assertThat(seen).hasSize(1); // held before the second model call
        handle.steer("while you wait: be brief");
        handle.resume();
        runner.join(5000);

        assertThat(out[0].isCompleted()).isTrue();
        assertThat(sentText(seen.get(1))).contains("while you wait: be brief");
        assertThat(events).containsExactly("paused", "resumed");
    }

    @Test
    void cancelWakesAPausedRun() throws Exception {
        RunHandle handle = new RunHandle();
        List<LLMRequest> seen = new CopyOnWriteArrayList<>();
        CountDownLatch paused = new CountDownLatch(1);
        LLMClient client = scripted(seen, () -> handle.pause(), ECHO, DONE);
        AgentEventListener listener =
                new AgentEventListener() {
                    public void onThought(String t) {}

                    public void onAction(String n, String i) {}

                    public void onObservation(String o) {}

                    @Override
                    public void onPaused() {
                        paused.countDown();
                    }
                };
        ReActAgent agent = agent(client, listener);
        AgentResult[] out = new AgentResult[1];
        Thread runner = new Thread(() -> out[0] = agent.run("do it", handle));
        runner.start();

        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        runner.join(5000);

        assertThat(out[0].isCancelled()).isTrue();
        assertThat(seen).hasSize(1);
    }

    @Test
    void aHandleCancelledBeforeTheRunStartsEndsTheRunCancelledWithoutCallingTheModel() {
        RunHandle handle = new RunHandle();
        handle.cancel();
        List<LLMRequest> seen = new ArrayList<>();
        LLMClient client = scripted(seen, () -> {}, DONE);

        AgentResult result = agent(client).run("x", handle);

        assertThat(result.isCancelled()).isTrue();
        assertThat(seen).isEmpty();
    }

    @Test
    void aHandleControlsOneRunAtATime() throws Exception {
        RunHandle handle = new RunHandle();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LLMClient client =
                scripted(
                        new ArrayList<>(),
                        () -> {
                            inside.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        DONE);
        Thread first = new Thread(() -> agent(client).run("x", handle));
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> agent(client).run("y", handle))
                .isInstanceOf(IllegalStateException.class);
        release.countDown();
        first.join(5000);
    }

    @Test
    void aRunWithoutAHandleIsUnchanged() {
        LLMClient client = scripted(new ArrayList<>(), () -> {}, ECHO, DONE);
        AgentResult result = agent(client).run("do it");
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.isCancelled()).isFalse();
    }
}
