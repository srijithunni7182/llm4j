package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The corners of the store, the answers and the listener: odd input, a channel that fails, a store with no triggers. */
class ChannelEdgeTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    static final String RUN = "/srv/runs/edge";

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    String ask(String step, Hints hints) {
        assertThatThrownBy(() -> h.asker(RUN).promptHuman(step, "Q " + step, hints)).isInstanceOf(RunSuspended.class);
        return h.pending.find(RUN, step).orElseThrow().code();
    }

    @Test
    @Tag("RA-V2.6")
    void answersAreRefusedForAnEmptyTextAndNeverNeedATriggerStore() {
        String free = ask("A/s0", Hints.none());
        Answers noTriggers = new Answers(h.pending, null, new Audit(h.pending, h.clock), h.clock);

        assertThat(noTriggers.record(free, "   ", "t").message()).contains("is empty");
        assertThat(noTriggers.record(free, null, "t").recorded()).isFalse();
        assertThat(noTriggers.record(free, "fine", "t").recorded()).isTrue();
        noTriggers.expire(h.pending.get(free).orElseThrow());
        assertThat(h.pending.get(free).orElseThrow().state()).as("an answered question is not expired afterwards").isEqualTo(Pending.State.ANSWERED);

        String other = ask("A/s1", Hints.none());
        noTriggers.expire(h.pending.get(other).orElseThrow());
        assertThat(h.pending.get(other).orElseThrow().state()).isEqualTo(Pending.State.EXPIRED);
        assertThat(h.triggers.all()).isEmpty();

        String third = ask("A/s2", Hints.none());
        h.answers.record(third, "console says so", "console", false);
        assertThat(h.triggers.all()).as("a console answer leaves no trigger").isEmpty();
        assertThat(h.answers.record("x".repeat(40), "y", "t").message()).contains("…");
        assertThat(h.answers.record(null, "y", "t").recorded()).isFalse();
    }

    @Test
    @Tag("RA-V3.3")
    void aReplyWhoseIdsAreNotNumbersIsIgnored() throws Exception {
        ask("A/s0", Hints.none());
        List<Channel.Reply> replies = List.of(new Channel.Reply("not-a-number", "5550001", "hello", null), new Channel.Reply("5550001", "someone", "hello", null));
        Listener listener = listenerOver(batchOf(replies));

        Listener.Summary s = listener.pollOnce(Duration.ZERO);

        assertThat(s.ignored()).isEqualTo(2);
        assertThat(h.pending.open()).hasSize(1);
    }

    @Test
    @Tag("RA-V3.5")
    void helpSaysWhenNothingIsWaitingAndWhenACodeIsNoLongerOpen() throws Exception {
        List<String> told = new ArrayList<>();
        Listener listener = listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", "yes", null)), told, false));
        listener.pollOnce(Duration.ZERO);
        assertThat(told).containsExactly("Nothing is waiting for an answer.");

        String code = ask("A/s0", Hints.none());
        h.answers.record(code, "done", "t");
        told.clear();
        listener = listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", code + " again", null)), told, false));
        listener.pollOnce(Duration.ZERO);
        assertThat(told).hasSize(1);
        assertThat(told.get(0)).contains("already answered");
    }

    @Test
    @Tag("RA-V3.7")
    void aNoteThatCannotBeSentIsLoggedNotFatalAndAnExpiryNoticeSurvivesAFailingChannel() throws Exception {
        String code = ask("A/s0", Hints.none());
        List<String> told = new ArrayList<>();
        Stub failing = new Stub(List.of(new Channel.Reply("5550001", "5550001", code + " ok", null)), told, true);
        Listener listener = listenerOver(failing);

        assertThat(listener.pollOnce(Duration.ZERO).recorded()).isEqualTo(1);

        String second = ask("A/s1", Hints.none());
        ChannelConfig expiring = h.config(null, 0, Duration.ofDays(1), Map.of("default", List.of(ChannelHarness.ME)));
        Listener expiringListener = new Listener(failing, expiring, h.pending, h.answers, new Dispatch(failing, expiring, h.pending, h.clock), h.clock);
        h.clock.advance(Duration.ofDays(2));
        expiringListener.maintain();
        assertThat(h.pending.get(second).orElseThrow().state()).isEqualTo(Pending.State.EXPIRED);
    }

    @Test
    @Tag("RA-V3.7")
    void aSendThatFailsForEveryChatLeavesTheQuestionUnsentAndACommandChannelWithNoChatsStillSends() throws Exception {
        Stub failing = new Stub(List.of(), new ArrayList<>(), true);
        ChannelConfig config = h.config(null, 0, null, Map.of("default", List.of(ChannelHarness.ME)));
        Pending p = new Pending("ABCDEF", RUN, "r", "S", "Q", List.of(), "prompt", null, ChannelHarness.T0);
        assertThat(new Dispatch(failing, config, h.pending, h.clock).send(p)).isFalse();
        assertThat(p.sent()).isEmpty();

        Path log = dir.resolve("bridge.log");
        CommandChannel bridge = new CommandChannel(List.of("sh", "-c", "cat >> '" + log + "'"));
        ChannelConfig none = new ChannelConfig("command", "X", Map.of(), null, 0, null, List.of("x"), "x");
        assertThat(new Dispatch(bridge, none, h.pending, h.clock).send(p)).isTrue();
        assertThat(Files.readString(log)).contains("ABCDEF");
        assertThat(p.sent()).hasSize(1);
    }

    @Test
    @Tag("RA-V2.6")
    void theStoreIgnoresFilesThatAreNotQuestionsAndRefusesCodesThatCouldBePaths() throws Exception {
        String code = ask("A/s0", Hints.none());
        Files.writeString(h.pending.root().resolve("questions/ZZZZZZ.json"), "{not json");
        Files.writeString(h.pending.root().resolve("questions/notes.txt"), "hello");

        assertThat(h.pending.all()).hasSize(1);
        assertThat(h.pending.get("../../x")).isEmpty();
        assertThat(h.pending.get(null)).isEmpty();
        assertThat(h.pending.get(code.toLowerCase())).isPresent();
        assertThat(new PendingStore(dir.resolve("nowhere")).all()).isEmpty();
        assertThat(new PendingStore(dir.resolve("nowhere")).exists()).isFalse();
        assertThat(h.pending.exists()).isTrue();
    }

    @Test
    @Tag("RA-V4.7")
    void aSingleChatIdIsReadAndABadOneIsRefusedWithWhatIsWrong() throws Exception {
        Path store = dir.resolve("cfg");
        Files.createDirectories(store);
        Files.writeString(store.resolve("channel.json"), "{\"channel\":\"telegram\",\"chats\":{\"default\":5,\"lead\":[6,\"7\"]},\"remind\":{\"every\":\"90m\"}}");
        ChannelConfig c = ChannelConfig.load(store, null, k -> null).orElseThrow();
        assertThat(c.chatsFor("lead")).containsExactly(6L, 7L);
        assertThat(c.chatsFor(null)).containsExactly(5L);
        assertThat(c.remindEvery()).hasMinutes(90);
        assertThat(c.remindAtMost()).isEqualTo(1);

        Files.writeString(store.resolve("channel.json"), "{\"channel\":\"telegram\",\"chats\":{\"default\":[\"me\"]}}");
        assertThatThrownBy(() -> ChannelConfig.load(store, null, k -> null)).hasMessageContaining("not a chat id");
        Files.writeString(store.resolve("channel.json"), "{\"channel\":\"telegram\",\"expire\":\"soon\"}");
        assertThatThrownBy(() -> ChannelConfig.load(store, null, k -> null)).hasMessageContaining("not a time span");
        Files.writeString(store.resolve("channel.json"), "{ nope");
        assertThatThrownBy(() -> ChannelConfig.load(store, null, k -> null)).hasMessageContaining("not valid JSON");
        assertThat(new ChannelConfig("telegram", "T", Map.of("a", List.of(1L)), null, 0, null, List.of(), "x").chatsFor("zzz")).containsExactly(1L);
        assertThat(new ChannelConfig("telegram", "T", Map.of(), null, 0, null, List.of(), "x").chatsFor("zzz")).isEmpty();
    }

    @Test
    @Tag("RA-V3.4")
    void freeTextQuestionsTakeTheWholeReplyAndAnApprovalWithNoChoicesStillNeedsItsCode() throws Exception {
        String approval = ask("A/s0", new Hints(Hints.Kind.APPROVAL, List.of(), null));
        List<String> told = new ArrayList<>();
        Listener bare = listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", "go ahead", null)), told, false));
        bare.pollOnce(Duration.ZERO);
        assertThat(told.get(0)).contains("needs the code").contains(approval + " yes");

        Listener coded = listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", approval, null)), told, false));
        coded.pollOnce(Duration.ZERO);
        assertThat(told.get(1)).as("a code with nothing after it").contains("is empty");

        h.answers.record(approval, "ok", "t");
        String free = ask("A/s1", Hints.none());
        String second = ask("A/s2", Hints.none());
        told.clear();
        listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", "words", null)), told, false)).pollOnce(Duration.ZERO);
        assertThat(told.get(0)).contains("Which question?").contains("your answer").contains(free).contains(second);

        h.answers.record(second, "done", "t");
        told.clear();
        listenerOver(new Stub(List.of(new Channel.Reply("5550001", "5550001", "in reply to something else entirely", "999")), told, false)).pollOnce(Duration.ZERO);
        assertThat(h.pending.get(free).orElseThrow().answer().text()).as("a reply to an unknown message falls back to the only open question").isEqualTo("in reply to something else entirely");
    }

    @Test
    @Tag("RA-V4.4")
    void nothingIsRemindedOrExpiredWhenTheChannelFileSaysNeitherAndAnAnsweredQuestionIsLeftAlone() throws Exception {
        String code = ask("A/s0", Hints.none());
        int before = h.telegram.sent.size();
        h.clock.advance(Duration.ofDays(30));
        h.runtime.listener().maintain();
        assertThat(h.telegram.sent).hasSize(before);
        assertThat(h.pending.get(code).orElseThrow().state()).isEqualTo(Pending.State.OPEN);

        Channels.Runtime reminding = h.runtimeWith(h.config(Duration.ofHours(1), 1, null, Map.of("default", List.of(ChannelHarness.ME))));
        h.answers.record(code, "ok", "t");
        reminding.listener().maintain();
        assertThat(h.telegram.sent).hasSize(before);
    }

    private Listener listenerOver(Channel channel) {
        return new Listener(channel, h.runtime.config(), h.pending, h.answers, new Dispatch(channel, h.runtime.config(), h.pending, h.clock), h.clock);
    }

    private Channel batchOf(List<Channel.Reply> replies) {
        return new Stub(replies, new ArrayList<>(), false);
    }

    /** A channel that returns given replies, records the notes sent back, and can fail every send and note. */
    static final class Stub implements Channel {
        private final List<Reply> replies;
        private final List<String> told;
        private final boolean failing;

        Stub(List<Reply> replies, List<String> told, boolean failing) {
            this.replies = replies;
            this.told = told;
            this.failing = failing;
        }

        public String name() { return "stub"; }

        public Sent send(String chat, Outgoing q) throws IOException {
            if (failing) throw new IOException("down");
            return new Sent(chat, "r1", null);
        }

        public Batch poll(Duration wait) { return new Batch(replies, "c"); }

        public void acknowledge(String cursor) { }

        public void tell(String chat, String text) throws IOException {
            if (failing) throw new IOException("down");
            told.add(text);
        }
    }
}
