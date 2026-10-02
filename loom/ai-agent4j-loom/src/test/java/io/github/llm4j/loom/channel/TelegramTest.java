package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Telegram, against a local stand-in for the Bot API (spec loom-remote-answers R3). */
class TelegramTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    HumanInterface person;
    static final long STRANGER = 999L;

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
        person = h.asker("/srv/runs/triage-42");
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    String ask(String step, boolean approval) {
        assertThatThrownBy(() -> person.promptHuman(step, "Decide " + step, new Hints(approval ? Hints.Kind.APPROVAL : Hints.Kind.DECIDE, List.of("approve", "reject", "escalate"), null)))
                .isInstanceOf(RunSuspended.class);
        return h.pending.find("/srv/runs/triage-42", step).orElseThrow().code();
    }

    @Test
    @Tag("RA-V3.1")
    void aFailureThatEchoesTheAddressNeverCarriesTheToken() {
        h.telegram.failSends = 1;
        h.telegram.echoUrlInError = true;
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Q?")).isInstanceOf(RunSuspended.class);

        TelegramChannel channel = new TelegramChannel(h.telegram.base(), FakeTelegram.TOKEN, dir.resolve("o.json"));
        h.telegram.failSends = 1;
        h.telegram.echoUrlInError = true;
        assertThatThrownBy(() -> channel.tell("1", "x")).isInstanceOf(IOException.class).hasMessageNotContaining(FakeTelegram.TOKEN).hasMessageNotContaining("FAKE-SECRET");
        h.telegram.failPolls = 1;
        assertThatThrownBy(() -> channel.poll(Duration.ZERO)).isInstanceOf(IOException.class).hasMessageNotContaining(FakeTelegram.TOKEN).hasMessageNotContaining("FAKE-SECRET");
        TelegramChannel wrong = new TelegramChannel(h.telegram.base(), "999:WRONG-TOKEN", dir.resolve("o2.json"));
        assertThatThrownBy(() -> wrong.tell("1", "x")).isInstanceOf(IOException.class).hasMessageNotContaining("WRONG-TOKEN");
        assertThat(h.telegram.paths).anyMatch(p -> p.contains("WRONG-TOKEN"));
    }

    @Test
    @Tag("RA-V3.2")
    void textIsSentAsTypedWithNoMarkupModeAndALongOneIsCutButKeptWhole() {
        String tricky = "*bold* [click](http://evil.example) <b>x</b> `code` _it_";
        assertThatThrownBy(() -> person.promptHuman("Main/s0", tricky)).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.last().hasParseMode()).isFalse();
        assertThat(h.telegram.last().text()).contains(tricky);

        String longText = "x".repeat(5000);
        assertThatThrownBy(() -> person.promptHuman("Main/s1", longText)).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.last().text().length()).isLessThanOrEqualTo(4096);
        assertThat(h.telegram.last().text()).contains("(cut)");
        assertThat(h.pending.find("/srv/runs/triage-42", "Main/s1").orElseThrow().question()).hasSize(5000);
    }

    @Test
    @Tag("RA-V3.3")
    void aReplyFromAChatOrAUserThatMayNotAnswerIsIgnoredNotAnsweredAndCounted() throws Exception {
        String code = ask("A/s0", false);
        h.telegram.reply(STRANGER, code + " approve");
        h.telegram.reply(ChannelHarness.ME, STRANGER, code + " approve", null); // inside an allowed chat, from someone who may not answer

        Listener.Summary s = h.runtime.listener().pollOnce(Duration.ZERO);

        assertThat(s.ignored()).isEqualTo(2);
        assertThat(s.recorded()).isZero();
        assertThat(h.pending.get(code).orElseThrow().state()).isEqualTo(Pending.State.OPEN);
        assertThat(h.telegram.sent).as("a stranger is never answered").hasSize(1);
        assertThat(h.runtime.listener().ignoredTotal()).isEqualTo(2);
    }

    @Test
    @Tag("RA-V3.4")
    void aReplyIsMatchedByReplyToByCodeByTheOnlyOpenQuestionOrElseHelpIsGiven() throws Exception {
        String a = ask("A/s0", false);
        int aMessage = h.telegram.last().messageId();

        h.telegram.reply(ChannelHarness.ME, ChannelHarness.ME, "approve", aMessage);
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.pending.get(a).orElseThrow().answer().text()).as("by reply-to").isEqualTo("approve");

        String b = ask("A/s1", false);
        h.telegram.reply(ChannelHarness.ME, "#" + b.toLowerCase() + " reject");
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.pending.get(b).orElseThrow().answer().text()).as("by code, any case, with a hash").isEqualTo("reject");

        String c = ask("A/s2", false);
        h.telegram.reply(ChannelHarness.ME, "esc");
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.pending.get(c).orElseThrow().answer().text()).as("the only open question").isEqualTo("escalate");

        String first = ask("A/s5", false);
        h.clock.advance(Duration.ofSeconds(1));
        String second = ask("A/s6", false);
        h.telegram.reply(ChannelHarness.ME, first.toLowerCase() + " approve");
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.pending.get(first).orElseThrow().answer().text()).as("a code names its own question, not another open one").isEqualTo("approve");
        assertThat(h.pending.get(second).orElseThrow().state()).isEqualTo(Pending.State.OPEN);
        h.answers.record(second, "reject", "test");

        String d = ask("A/s3", false);
        String e = ask("A/s4", false);
        h.telegram.reply(ChannelHarness.ME, "approve");
        Listener.Summary s = h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(s.recorded()).isZero();
        assertThat(h.telegram.last().text()).contains("Which question?").contains(d).contains(e);
        assertThat(h.pending.open()).hasSize(2);
    }

    @Test
    @Tag("RA-V3.5")
    void anAcceptedAnswerIsConfirmedAndAReplyNotUnderstoodGetsHelp() throws Exception {
        String code = ask("A/s0", false);

        h.telegram.reply(ChannelHarness.ME, code + " maybe");
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.telegram.last().text()).contains("Not understood").contains("approve, reject, escalate");

        h.telegram.reply(ChannelHarness.ME, code + " approve");
        h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(h.telegram.last().text()).isEqualTo("Recorded: approve for " + code + ".");
    }

    @Test
    @Tag("RA-V3.6")
    void aRestartNeitherAppliesOldRepliesAgainNorMissesNewOnes() throws Exception {
        String code = ask("A/s0", false);
        h.telegram.reply(ChannelHarness.ME, code + " approve");

        // the process dies after recording the answer and before the channel is told to forget the reply
        TelegramChannel real = new TelegramChannel(h.telegram.base(), FakeTelegram.TOKEN, h.pending.root().resolve("offset.json"));
        Channel crashing = new Channel() {
            public String name() { return real.name(); }
            public Sent send(String chat, Outgoing q) throws IOException { return real.send(chat, q); }
            public Batch poll(Duration wait) throws IOException { return real.poll(wait); }
            public void acknowledge(String cursor) throws IOException { throw new IOException("killed"); }
            public void tell(String chat, String text) throws IOException { real.tell(chat, text); }
        };
        Listener dying = listenerOver(crashing);
        assertThatThrownBy(() -> dying.pollOnce(Duration.ZERO)).hasMessage("killed");
        assertThat(h.triggers.all()).hasSize(1);

        // the restart reads the same update again: it applies once, not twice
        Listener restarted = listenerOver(new TelegramChannel(h.telegram.base(), FakeTelegram.TOKEN, h.pending.root().resolve("offset.json")));
        Listener.Summary again = restarted.pollOnce(Duration.ZERO);
        assertThat(again.recorded()).isZero();
        assertThat(h.pending.get(code).orElseThrow().answer().text()).isEqualTo("approve");
        assertThat(h.triggers.all()).hasSize(1);

        // and now it is forgotten: the next poll asks for what comes after it
        String next = ask("A/s1", false);
        h.telegram.reply(ChannelHarness.ME, next + " reject");
        assertThat(restarted.pollOnce(Duration.ZERO).recorded()).isEqualTo(1);
        assertThat(h.telegram.lastOffset).isGreaterThan(100);
        assertThat(h.pending.get(next).orElseThrow().answer().text()).isEqualTo("reject");
        assertThat(restarted.pollOnce(Duration.ZERO).recorded()).isZero();
    }

    private Listener listenerOver(Channel channel) {
        Dispatch dispatch = new Dispatch(channel, h.runtime.config(), h.pending, h.clock);
        return new Listener(channel, h.runtime.config(), h.pending, h.answers, dispatch, h.clock);
    }

    @Test
    @Tag("RA-V3.7")
    void aFailingServerNeverStopsTheListenerAndAFailedSendIsRetriedAtTheNextTick() throws Exception {
        h.telegram.failSends = 1;
        assertThatThrownBy(() -> person.promptHuman("Main/s0", "Q?")).isInstanceOf(RunSuspended.class);
        Pending unsent = h.pending.all().get(0);
        assertThat(unsent.sent()).isEmpty();
        assertThat(h.telegram.sent).isEmpty();

        h.runtime.listener().maintain();
        assertThat(h.pending.all().get(0).sent()).hasSize(1);
        assertThat(h.telegram.sent).hasSize(1);

        // polls fail twice, then the server recovers; the loop backs off and carries on
        h.telegram.failPolls = 2;
        h.telegram.reply(ChannelHarness.ME, unsent.code() + " done");
        AtomicBoolean stop = new AtomicBoolean();
        Thread loop = new Thread(() -> h.runtime.listen(Duration.ZERO, stop, last -> Duration.ofMillis(20)));
        loop.start();
        long until = System.currentTimeMillis() + 5000;
        while (h.pending.get(unsent.code()).orElseThrow().open() && System.currentTimeMillis() < until) Thread.sleep(20);
        stop.set(true);
        loop.join(2000);

        assertThat(h.pending.get(unsent.code()).orElseThrow().answer().text()).isEqualTo("done");
    }

    @Test
    @Tag("RA-V4.1")
    void theCommandLineChannelWinsOverTheFileAndTheFileOverTheEnvironment() throws Exception {
        Path store = dir.resolve("s2");
        java.nio.file.Files.createDirectories(store);
        java.nio.file.Files.writeString(store.resolve("channel.json"), "{\"channel\":\"command\",\"command\":[\"cat\"],\"chats\":{\"default\":[1]}}");
        Map<String, String> env = Map.of("TELEGRAM_CHAT_IDS", "42", "TELEGRAM_BOT_TOKEN", "t");

        assertThat(ChannelConfig.load(store, null, env::get).orElseThrow().channel()).isEqualTo("command");
        assertThat(ChannelConfig.load(store, "telegram", env::get).orElseThrow().channel()).isEqualTo("telegram");
        assertThat(ChannelConfig.load(store, "console", env::get)).isEmpty();
        assertThat(ChannelConfig.load(dir.resolve("none"), null, env::get)).as("no file, no flag: the console").isEmpty();
        assertThat(ChannelConfig.load(dir.resolve("none"), "telegram", env::get).orElseThrow().chatsFor(null)).containsExactly(42L);
    }
}
