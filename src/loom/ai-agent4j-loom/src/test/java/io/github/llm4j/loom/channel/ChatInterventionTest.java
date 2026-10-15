package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.InterventionCommands;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** People who may answer a chat may also steer, wrap up and cancel the runs going on in the same process; a reply to a question is still an answer. */
class ChatInterventionTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    final List<String> told = new ArrayList<>();
    final List<Channel.Reply> replies = new ArrayList<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    final class Scripted implements Channel {
        public String name() { return "scripted"; }
        public Sent send(String chat, Outgoing q) { return new Sent(chat, "x1", null); }
        public Batch poll(Duration wait) {
            List<Reply> now = List.copyOf(replies);
            replies.clear();
            return new Batch(now, "c");
        }
        public void acknowledge(String cursor) { }
        public void tell(String chat, String text) throws IOException { told.add(text); }
    }

    Listener listener() {
        Scripted channel = new Scripted();
        return new Listener(channel, h.runtime.config(), h.pending, h.answers, new Dispatch(channel, h.runtime.config(), h.pending, h.clock), h.clock)
                .withCommands(InterventionCommands.forThisProcess());
    }

    Channel.Reply reply(String text) {
        return new Channel.Reply("5550001", "5550001", text, null, null, null);
    }

    /** A run whose one agent waits on the test inside its first model call. */
    LoomRun liveRun(String label) throws Exception {
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```").model("m").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HarnessExecutor ex = new HarnessExecutor(new LoomParser(new Lexer("""
                agent Writer { model: "m" system: "You are Writer." tools: [calculator] steerable: true }
                workflow Main() { delegate "write" to Writer -> out_text }
                """).tokenize()).parseScript(), new ToolRegistry(), name -> model);
        ex.setJournal(RunJournal.inMemory());
        ex.initialize();
        LoomRun run = LoomRun.start(ex, label, "Main", Map.of(), "test");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        return run;
    }

    @Test
    void anAllowedPersonCanSteerARunInThisProcessFromTheChat() throws Exception {
        LoomRun run = liveRun("chat-run");
        replies.add(reply("steer Writer use the EU figures"));
        Listener.Summary s = listener().pollOnce(Duration.ZERO);
        assertThat(s.recorded()).isEqualTo(1);
        assertThat(told).hasSize(1);
        assertThat(told.get(0)).startsWith("Sent to Writer");
        release.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThat(run.executor().getJournal().all().values()).anyMatch(e -> "steer".equals(e.kind())
                && String.valueOf(e.value()).contains("scripted:5550001") && String.valueOf(e.value()).contains("use the EU figures"));
    }

    @Test
    void aPersonWhoMayNotAnswerCannotSteer() throws Exception {
        LoomRun run = liveRun("stranger-run");
        replies.add(new Channel.Reply("999", "999", "steer Writer take over", null, null, null));
        Listener.Summary s = listener().pollOnce(Duration.ZERO);
        assertThat(s.ignored()).isEqualTo(1);
        assertThat(run.executor().getJournal().all().values()).noneMatch(e -> "steer".equals(e.kind()));
        release.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void aReplyThatIsOneOfAQuestionsChoicesIsAnAnswerEvenWhenItLooksLikeACommand() throws Exception {
        LoomRun run = liveRun("both-run");
        assertThatThrownBy(() -> h.asker("/srv/runs/r").promptHuman("A/s0", "Go back?", new Hints(Hints.Kind.APPROVAL, List.of("keep", "repeat", "cancel"), null)))
                .isInstanceOf(RunSuspended.class);
        String code = h.pending.find("/srv/runs/r", "A/s0").orElseThrow().code();
        replies.add(reply(code + " cancel"));
        listener().pollOnce(Duration.ZERO);
        assertThat(h.pending.get(code).orElseThrow().answer().text()).isEqualTo("cancel");
        assertThat(run.status()).isEqualTo(LoomRun.Status.RUNNING); // the agent was not cancelled
        release.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void withNoRunInThisProcessACommandIsToldSo() throws Exception {
        replies.add(reply("steer Writer hello"));
        listener().pollOnce(Duration.ZERO);
        assertThat(told).containsExactly("Nothing is running here.");
    }
}
