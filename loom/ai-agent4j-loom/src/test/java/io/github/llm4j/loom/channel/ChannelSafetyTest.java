package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.HumanInterface.Hints;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who may answer, what an answer may be, and what never leaves the machine (spec loom-remote-answers R5). */
class ChannelSafetyTest {

    @TempDir
    Path dir;

    ChannelHarness h;
    HumanInterface person;
    static final String RUN = "/srv/runs/triage-42";

    @BeforeEach
    void setUp() throws Exception {
        h = new ChannelHarness(dir.resolve("store"));
        person = h.asker(RUN);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    String ask(String step, Hints.Kind kind, List<String> choices) {
        assertThatThrownBy(() -> person.promptHuman(step, "Question " + step, new Hints(kind, choices, null))).isInstanceOf(RunSuspended.class);
        return h.pending.find(RUN, step).orElseThrow().code();
    }

    @Test
    @Tag("RA-V5.1")
    void onlyAnAllowedSenderForAnOpenQuestionEverAnswers() throws Exception {
        String open = ask("A/s0", Hints.Kind.DECIDE, List.of("approve", "reject"));
        String answered = ask("A/s1", Hints.Kind.DECIDE, List.of("approve", "reject"));
        h.answers.record(answered, "approve", "first");
        String expired = ask("A/s2", Hints.Kind.DECIDE, List.of("approve", "reject"));
        h.answers.expire(h.pending.get(expired).orElseThrow());

        long[][] senders = {{ChannelHarness.ME, ChannelHarness.ME}, {999, 999}, {ChannelHarness.ME, 999}, {999, ChannelHarness.ME}, {0, 0}, {-1, ChannelHarness.ME}};
        String[] codes = {open, answered, expired, "ZZZZZZ", "", "A/s0", open.toLowerCase()};
        int recordedFromAllowed = 0;
        for (long[] s : senders) {
            for (String code : codes) {
                h.telegram.reply(s[0], s[1], code + " reject", null);
            }
        }
        Listener.Summary sum = h.runtime.listener().pollOnce(Duration.ZERO);
        recordedFromAllowed = sum.recorded();

        assertThat(recordedFromAllowed).as("exactly one reply is from an allowed sender to a question that is open (its lower-case form arrives after it is closed)").isEqualTo(1);
        assertThat(h.pending.get(open).orElseThrow().answer().by()).isEqualTo("telegram:" + ChannelHarness.ME);
        assertThat(h.pending.get(answered).orElseThrow().answer().by()).isEqualTo("first");
        assertThat(h.pending.get(expired).orElseThrow().answer()).isNull();
    }

    @Test
    @Tag("RA-V5.2")
    void codesCarryAtLeastTwentyFiveBitsComeFromASecureSourceAndNeverClash() {
        assertThat(Codes.bits()).isGreaterThanOrEqualTo(25.0);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) seen.add(Codes.fresh(c -> false));
        assertThat(seen.size()).as("no repeats in 20 000 draws at 29 bits").isGreaterThan(19_900);
        assertThat(seen).allSatisfy(c -> assertThat(Codes.looksLikeOne(c)).isTrue());

        List<String> first = new ArrayList<>();
        String next = Codes.fresh(c -> {
            if (first.size() < 3) {
                first.add(c);
                return true;
            }
            return false;
        });
        assertThat(first).as("a clash draws again").hasSize(3).doesNotContain(next);
        assertThat(Codes.class.getDeclaredFields()).extracting(f -> f.getType().getSimpleName()).contains("SecureRandom");
    }

    @Test
    @Tag("RA-V5.3")
    void anApprovalNeedsTheCodeInTheReplyWhateverElseIsTrue() throws Exception {
        String code = ask("A/s0#approve:Mail:1", Hints.Kind.APPROVAL, List.of("yes", "no"));
        int message = h.telegram.last().messageId();
        assertThat(h.telegram.last().text()).doesNotContain("reply directly");

        h.telegram.reply(ChannelHarness.ME, "yes");
        h.telegram.reply(ChannelHarness.ME, ChannelHarness.ME, "yes", message);
        Listener.Summary bare = h.runtime.listener().pollOnce(Duration.ZERO);
        assertThat(bare.recorded()).isZero();
        assertThat(h.pending.get(code).orElseThrow().state()).isEqualTo(Pending.State.OPEN);
        assertThat(h.telegram.last().text()).contains("needs the code").contains(code);

        h.telegram.reply(ChannelHarness.ME, code + " yes");
        assertThat(h.runtime.listener().pollOnce(Duration.ZERO).recorded()).isEqualTo(1);
        assertThat(h.pending.get(code).orElseThrow().answer().text()).isEqualTo("yes");
    }

    @Test
    @Tag("RA-V5.5")
    void controlCharactersAreNeutralisedAndAReplyIsOnlyEverTextComparedWithTheChoices() throws Exception {
        String code = ask("A/s0", Hints.Kind.DECIDE, List.of("approve", "reject"));
        for (String hostile : new String[] {"; rm -rf /", "../../x", "$(reboot)", "approve; reject", "`id`", "approve\u0000"}) {
            Answers.Outcome o = h.answers.record(code, hostile, "t");
            assertThat(o.recorded()).as(hostile).isFalse();
        }
        assertThat(h.pending.get(code).orElseThrow().state()).isEqualTo(Pending.State.OPEN);

        String free = ask("A/s1", Hints.Kind.PROMPT, List.of());
        h.answers.record(free, "line one\r\nline\u0007 two ‮", "t");
        assertThat(h.pending.get(free).orElseThrow().answer().text()).isEqualTo("line one??line? two ?");

        assertThatThrownBy(() -> person.promptHuman("A/s2", "case value\u0007 with a bell\u001b[31m and an escape")).isInstanceOf(RunSuspended.class);
        assertThat(h.telegram.last().text()).doesNotContain("\u0007").doesNotContain("\u001b");
    }

    @Test
    @Tag("RA-V5.6")
    void theBotTokenIsNowhereInTheStoreTheAuditLogTheLogsOrTheErrors() throws Exception {
        List<String> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try {
            h.telegram.echoUrlInError = true;
            h.telegram.failSends = 1;
            ask("A/s0", Hints.Kind.DECIDE, List.of("approve"));
            h.runtime.listener().maintain();
            String code = h.pending.all().get(0).code();
            h.telegram.reply(ChannelHarness.ME, code + " approve");
            h.telegram.failPolls = 1;
            try {
                h.runtime.listener().pollOnce(Duration.ZERO);
            } catch (java.io.IOException e) {
                logged.add(e.getMessage());
            }
            h.runtime.listener().pollOnce(Duration.ZERO);
        } finally {
            root.removeHandler(capture);
        }

        List<String> everything = new ArrayList<>(logged);
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) everything.add(Files.readString(f));
        }
        assertThat(everything).isNotEmpty().allSatisfy(text -> assertThat(String.valueOf(text)).doesNotContain(FakeTelegram.TOKEN).doesNotContain("FAKE-SECRET"));
    }

    @Test
    @Tag("RA-V5.7")
    void aChannelThatCannotBeUsedFailsAtStartAndNamesWhatIsMissingNeverAcceptingEveryone() {
        Path store = dir.resolve("s");
        assertThatThrownBy(() -> Channels.open(store, "telegram", Map.of("TELEGRAM_CHAT_IDS", "5")::get, h.clock)).hasMessageContaining("TELEGRAM_BOT_TOKEN");
        assertThatThrownBy(() -> Channels.open(store, "telegram", Map.of("TELEGRAM_BOT_TOKEN", "t")::get, h.clock)).hasMessageContaining("nobody is allowed to answer").hasMessageContaining("TELEGRAM_CHAT_IDS");
        assertThatThrownBy(() -> Channels.open(store, "command", Map.<String, String>of()::get, h.clock)).hasMessageContaining("command");
        assertThatThrownBy(() -> Channels.open(store, "carrier-pigeon", Map.<String, String>of()::get, h.clock)).hasMessageContaining("unknown channel");
        assertThat(Channels.open(store, "telegram", Map.of("TELEGRAM_BOT_TOKEN", "t", "TELEGRAM_CHAT_IDS", "5, 6")::get, h.clock)).isPresent();
    }
}
