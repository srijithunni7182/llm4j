package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the listener matches a reply to a question, with a scripted channel and a fixed clock (no HTTP, no real time). */
class ListenerRepliesTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    final List<String> told = new ArrayList<>();
    final List<Channel.Reply> replies = new ArrayList<>();
    boolean tellFails;
    int refs;

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** Delivers every question with its own message ref, and hands out the scripted replies once. */
    final class Scripted implements Channel {
        public String name() { return "scripted"; }

        public Sent send(String chat, Outgoing q) {
            return new Sent(chat, "x" + (++refs), null);
        }

        public Batch poll(Duration wait) {
            List<Reply> now = List.copyOf(replies);
            replies.clear();
            return new Batch(now, "c");
        }

        public void acknowledge(String cursor) { }

        public void tell(String chat, String text) throws IOException {
            if (tellFails) throw new IOException("down");
            told.add(text);
        }

        @Override
        public boolean writtenBefore(String messageRef, String questionRef) {
            return messageRef.compareTo(questionRef) < 0;
        }
    }

    Listener listener() {
        Scripted channel = new Scripted();
        return new Listener(channel, h.runtime.config(), h.pending, h.answers, new Dispatch(channel, h.runtime.config(), h.pending, h.clock), h.clock);
    }

    String ask(String step, Hints hints) {
        assertThatThrownBy(() -> h.asker("/srv/runs/r").promptHuman(step, "Q " + step, hints)).isInstanceOf(RunSuspended.class);
        return h.pending.find("/srv/runs/r", step).orElseThrow().code();
    }

    Channel.Reply reply(String text, String replyToRef, Instant at, String ref) {
        return new Channel.Reply("5550001", "5550001", text, replyToRef, at, ref);
    }

    @Test
    void aReplyToOneOfTwoQuestionsAnswersThatOneAndACodeAnswersItsOwn() throws Exception {
        Listener listener = listener();
        String first = ask("A/s0", Hints.none());
        String second = ask("A/s1", Hints.none());
        String ref = h.pending.get(second).orElseThrow().sent().get(0).ref(); // asking delivers the question through the fake Telegram

        replies.add(reply("for the second", ref, null, null));
        Listener.Summary s = listener.pollOnce(Duration.ZERO);
        assertThat(s.recorded()).isEqualTo(1);
        assertThat(h.pending.get(second).orElseThrow().answer().text()).isEqualTo("for the second");
        assertThat(h.pending.get(first).orElseThrow().open()).isTrue();

        replies.add(reply(first.toLowerCase() + " the first", null, null, null));
        listener.pollOnce(Duration.ZERO);
        assertThat(h.pending.get(first).orElseThrow().answer().text()).as("the code is not part of the answer").isEqualTo("the first");
    }

    @Test
    void aReplyWrittenBeforeTheQuestionIsALeftoverAndIsNotAnAnswer() throws Exception {
        Listener listener = listener();
        String code = ask("A/s0", Hints.none());
        String ref = h.pending.get(code).orElseThrow().sent().get(0).ref();

        // by message ref: a ref the channel says was written before the question's; by date: older than the question minus the allowed skew
        replies.add(reply("old by ref", ref, null, "0"));
        replies.add(reply("old by date", null, ChannelHarness.T0.minus(Duration.ofHours(1)), null));
        Listener.Summary s = listener.pollOnce(Duration.ZERO);

        assertThat(s.stale()).isEqualTo(2);
        assertThat(s.recorded()).isZero();
        assertThat(told).as("a leftover is not answered, not even with help").isEmpty();
        assertThat(h.pending.get(code).orElseThrow().open()).isTrue();

        replies.add(reply("now", null, ChannelHarness.T0, "~z"));
        assertThat(listener.pollOnce(Duration.ZERO).recorded()).isEqualTo(1);
        assertThat(h.pending.get(code).orElseThrow().answer().text()).isEqualTo("now");
    }

    @Test
    void aNoteThatCannotBeSentBackDoesNotLoseTheAnswer() throws Exception {
        Listener listener = listener();
        String code = ask("A/s0", Hints.none());
        tellFails = true;
        replies.add(reply("fine", null, null, null));

        Listener.Summary s = listener.pollOnce(Duration.ZERO);

        assertThat(s.recorded()).isEqualTo(1);
        assertThat(h.pending.get(code).orElseThrow().answer().text()).isEqualTo("fine");
    }

    @Test
    void repliesFromPeopleWhoMayNotAnswerAreCountedAndNotedOnce() throws Exception {
        Listener listener = listener();
        ask("A/s0", Hints.none());
        replies.add(new Channel.Reply("1", "2", "hi", null));
        replies.add(new Channel.Reply("5550001", "7", "hi", null));
        listener.pollOnce(Duration.ZERO);
        replies.add(new Channel.Reply("1", "2", "again", null));
        listener.pollOnce(Duration.ZERO);

        assertThat(listener.ignoredTotal()).isEqualTo(3);
    }
}
