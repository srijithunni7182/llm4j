package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.channel.ChannelHarness;
import io.github.llm4j.loom.channel.FakeTelegram;
import io.github.llm4j.loom.channel.Pending;
import io.github.llm4j.loom.channel.PendingStore;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real runs that ask a person through a channel: pause, answer, resume (spec loom-remote-answers R1, R2, R4, R5). */
class ChannelRunTest {

    @TempDir
    Path dir;

    FakeTelegram telegram;
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-04-01T08:00:00Z"));
    Path store;
    int consoleAsked;

    static final String DIGEST = """
            workflow Main() {
                human_prompt "Publish today's digest? (yes/no)" -> go
                note "the answer was {go}"
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        telegram = new FakeTelegram();
        store = dir.resolve("store");
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    WeaveEnv env(String askVia, Map<String, String> vars) {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                var messages = request.getMessages();
                String last = messages.get(messages.size() - 1).getContent();
                int amount = Integer.parseInt(AutonomyCommandsTest.fields(last).getOrDefault("amount", "0"));
                return LLMResponse.builder().content("```json\n{\"choice\": \"" + (amount < 100 ? "approve" : "reject") + "\", \"reasoning\": \"SECRET-REASON about " + amount + "\", \"confidence\": 0.77}\n```")
                        .model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HumanInterface console = question -> {
            consoleAsked++;
            return "yes";
        };
        return new WeaveEnv(m -> client, console, new PrintStream(outBytes, true), new PrintStream(errBytes, true), clock, d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), vars::get, askVia);
    }

    Map<String, String> telegramVars() {
        return Map.of("TELEGRAM_BOT_TOKEN", FakeTelegram.TOKEN, "TELEGRAM_CHAT_IDS", String.valueOf(ChannelHarness.ME), "TELEGRAM_API_BASE", telegram.base());
    }

    File script(String name, String source) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), source);
        return f;
    }

    int run(File script, String workflow, Map<String, String> inputs, String runName, WeaveEnv env) {
        return WeaveCLI.run(script, null, workflow, inputs, null, null, null, null, dir.resolve("runs").resolve(runName), store, false, false, null, null, null, env);
    }

    String out() {
        return outBytes.toString();
    }

    @Test
    @Tag("RA-V1.1")
    @Tag("RA-V1.3")
    @Tag("RA-V2.1")
    @Tag("RA-V4.2")
    void aRunAsksOnPhonePausesAndOneTickTurnsTheReplyIntoACompletedRun() throws Exception {
        WeaveEnv env = env("telegram", telegramVars());
        int first = run(script("digest.loom", DIGEST), "Main", Map.of(), "d1", env);

        assertThat(first).as(errBytes.toString()).isEqualTo(4);
        assertThat(consoleAsked).as("nobody was asked at the console").isZero();
        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.last().text()).contains("[d1] Publish today's digest? (yes/no)");
        String code = new PendingStore(store).open().get(0).code();

        telegram.reply(ChannelHarness.ME, code + " yes");
        assertThat(WeaveCLI.tick(store, env)).isZero();

        assertThat(out()).contains("Recorded 1 answer(s)").contains("Workflow completed successfully");
        assertThat(Files.readString(dir.resolve("runs/d1/journal.json"))).contains("yes");
        assertThat(telegram.sent).as("the question, and the confirmation; nothing sent again").hasSize(2);
        assertThat(telegram.last().text()).startsWith("Recorded: yes for " + code);
        assertThat(new FileTriggerStore(store).all()).isEmpty();
    }

    @Test
    @Tag("RA-V1.6")
    void withNoChannelTheConsoleIsAskedExactlyAsBefore() throws Exception {
        int code = run(script("digest.loom", DIGEST), "Main", Map.of(), "d2", env(null, Map.of()));

        assertThat(code).isZero();
        assertThat(consoleAsked).isEqualTo(1);
        assertThat(out()).contains("Workflow completed successfully");
        assertThat(Files.exists(store.resolve("channel"))).isFalse();
    }

    @Test
    @Tag("RA-V1.7")
    void askingThroughAChannelWithoutAJournalStopsAtStartWithTheReason() throws Exception {
        int code = WeaveCLI.run(script("digest.loom", DIGEST), null, "Main", Map.of(), null, null, null, null, null, null, false, false, null, null, null, env("telegram", telegramVars()));

        assertThat(code).isNotZero();
        assertThat(errBytes.toString()).contains("--ask-via telegram needs --journal");
        assertThat(telegram.sent).isEmpty();
    }

    @Test
    @Tag("RA-V5.7")
    void aTickWithAChannelThatCannotBeUsedFailsAndSaysWhatIsMissing() {
        assertThat(WeaveCLI.tick(store, env("telegram", Map.of("TELEGRAM_CHAT_IDS", "5")))).isEqualTo(2);
        assertThat(errBytes.toString()).contains("TELEGRAM_BOT_TOKEN");
    }

    @Test
    @Tag("RA-V2.7")
    void aQuestionAnsweredAtTheConsoleClosesTheOneSentToTheChannel() throws Exception {
        File s = script("digest.loom", DIGEST);
        assertThat(run(s, "Main", Map.of(), "d3", env("telegram", telegramVars()))).isEqualTo(4);
        Pending asked = new PendingStore(store).open().get(0);

        int resumed = WeaveCLI.resume(dir.resolve("runs").resolve("d3"), env("console", Map.of()));

        assertThat(resumed).as(errBytes.toString()).isZero();
        assertThat(consoleAsked).isEqualTo(1);
        Pending closed = new PendingStore(store).get(asked.code()).orElseThrow();
        assertThat(closed.state()).isEqualTo(Pending.State.ANSWERED);
        assertThat(closed.answer().by()).isEqualTo("console");
        assertThat(new FileTriggerStore(store).all()).as("no resume trigger is left for a run that already finished").isEmpty();
    }

    @Test
    @Tag("RA-V4.6")
    @Tag("RA-V2.2")
    void questionsAndAnswerWorkFromATerminalWithNoChannelConfigured() throws Exception {
        assertThat(run(script("digest.loom", DIGEST), "Main", Map.of(), "d4", env("telegram", telegramVars()))).isEqualTo(4);
        String code = new PendingStore(store).open().get(0).code();
        WeaveEnv bare = env(null, Map.of());
        outBytes.reset();

        assertThat(AnswerCommands.questions(store, false, false, bare)).isZero();
        assertThat(out()).contains(code).contains("open").contains("d4").contains("Publish today's digest?");
        outBytes.reset();
        assertThat(AnswerCommands.questions(store, false, true, bare)).isZero();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(out()).get(0).get("code").asText()).isEqualTo(code);

        outBytes.reset();
        assertThat(AnswerCommands.answer(store, code, "yes", "operator:me", bare)).isZero();
        assertThat(out()).contains("Recorded: yes for " + code);
        assertThat(AnswerCommands.answer(store, code, "no", "operator:you", bare)).as("a second answer is refused").isEqualTo(1);
        assertThat(errBytes.toString()).contains("already answered by operator:me");

        outBytes.reset();
        assertThat(AnswerCommands.questions(store, false, false, bare)).isZero();
        assertThat(out()).contains("Nothing is waiting");
        outBytes.reset();
        AnswerCommands.questions(store, true, false, bare);
        assertThat(out()).contains("answered").contains("answered \"yes\" by operator:me");

        assertThat(WeaveCLI.tick(store, bare)).isZero();
        assertThat(out()).contains("Workflow completed successfully");
        assertThat(Files.readString(dir.resolve("runs/d4/journal.json"))).contains("yes");
    }

    @Test
    @Tag("RA-V4.3")
    void aDaemonListeningOnTheChannelCarriesTheRunOnWithinSeconds() throws Exception {
        WeaveEnv env = env("telegram", telegramVars());
        assertThat(run(script("digest.loom", DIGEST), "Main", Map.of(), "d5", env)).isEqualTo(4);
        String code = new PendingStore(store).open().get(0).code();
        telegram.reply(ChannelHarness.ME, code + " yes");

        Thread daemon = new Thread(() -> {
            try {
                WeaveCLI.daemon(store, Duration.ofMillis(100), Duration.ofSeconds(4), env);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long start = System.currentTimeMillis();
        daemon.start();
        while (!out().contains("Workflow completed successfully") && System.currentTimeMillis() - start < 8000) Thread.sleep(50);
        long took = System.currentTimeMillis() - start;
        daemon.join(8000);

        assertThat(out()).contains("Workflow completed successfully").contains("listening on telegram");
        assertThat(took).isLessThan(3500);
    }

    @Test
    @Tag("RA-V5.4")
    void aWatchQuestionItsReminderAndItsConfirmationNeverShowTheProposal() throws Exception {
        String refund = io.github.llm4j.loom.autonomy.Scripts2Access.refund("");
        Map<String, String> inputs = new LinkedHashMap<>(Map.of("ticket", "T-1", "tier", "gold", "amount", "50", "reason", "damaged", "customer_since", "2020"));
        WeaveEnv env = env("telegram", telegramVars());
        Files.createDirectories(store);
        Files.writeString(store.resolve("channel.json"), "{\"channel\":\"telegram\",\"remind\":{\"every\":\"1h\",\"atMost\":2}}");

        assertThat(run(script("refund.loom", refund), "Triage", inputs, "r1", env)).as(errBytes.toString()).isEqualTo(4);
        clock.advance(Duration.ofHours(2));
        WeaveCLI.tick(store, env);
        String code = new PendingStore(store).open().get(0).code();
        telegram.reply(ChannelHarness.ME, code + " approve");
        WeaveCLI.tick(store, env);

        assertThat(telegram.sent.size()).as("question, reminder, confirmation").isGreaterThanOrEqualTo(3);
        assertThat(telegram.sent).allSatisfy(s -> assertThat(s.text()).doesNotContain("SECRET-REASON").doesNotContain("0.77").doesNotContain("proposes").doesNotContain("proposal"));
        assertThat(telegram.sent.get(0).text()).contains("amount = 50").contains(code);
        assertThat(out()).contains("Workflow completed successfully");
        assertThat(Files.readString(store.resolve("autonomy/Refund/ledger.jsonl"))).contains("approve");
    }
}
