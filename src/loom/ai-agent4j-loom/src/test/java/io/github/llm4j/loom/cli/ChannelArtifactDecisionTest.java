package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.channel.ChannelHarness;
import io.github.llm4j.loom.channel.FakeTelegram;
import io.github.llm4j.loom.channel.PendingStore;
import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LedgerFold;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification I3: a decision that proposes an artifact is answered from a phone. At the blind rung
 * the question asks for the person's own work and shows no proposal, in the question, the reminder
 * or the confirmation; at the confirm rung it shows the proposal and takes accept, reject or a
 * corrected text.
 */
@Tag("L8")
class ChannelArtifactDecisionTest {

    static final String DRAFT = "SECRET-DRAFT about cats";

    static final String SCRIPT =
            """
            agent Writer { model: "m" system: "You are Writer." output_schema: { post: string, reasoning: string } }
            measure untouched(c) = c.response.kind == "accept"
            ladder P {
                rung draft { mode: blind }
                rung edit { mode: confirm climb when history.count >= 2 }
                rung publish { mode: autonomous climb when history.count >= 50 slip when history.count > 500 sample 10% blind }
                climbing is automatic
            }
            decision Publish {
                proposed by: Writer
                proposes: post
                remember: topic
                ask: editors
                ladder: P
                ceiling: edit
                when the agent changes: start over
            }
            workflow Triage(topic) {
                decide Publish -> post
                note "decided"
            }
            """;

    @TempDir Path dir;

    FakeTelegram telegram;
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-04-01T08:00:00Z"));
    Path store;
    File script;
    int runs;

    @BeforeEach
    void setUp() throws Exception {
        telegram = new FakeTelegram();
        store = dir.resolve("store");
        script = dir.resolve("publish.loom").toFile();
        Files.writeString(script.toPath(), SCRIPT);
    }

    @AfterEach
    void tearDown() {
        telegram.close();
    }

    WeaveEnv env() {
        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return LLMResponse.builder()
                                .content(
                                        "```json\n{\"post\": \""
                                                + DRAFT
                                                + "\", \"reasoning\": \"SECRET-WHY\"}\n```")
                                .model("m")
                                .tokenUsage(10, 5, 15)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }
                };
        Map<String, String> vars =
                Map.of(
                        "TELEGRAM_BOT_TOKEN",
                        FakeTelegram.TOKEN,
                        "TELEGRAM_CHAT_IDS",
                        String.valueOf(ChannelHarness.ME),
                        "TELEGRAM_API_BASE",
                        telegram.base());
        return new WeaveEnv(
                m -> client,
                q -> {
                    throw new AssertionError("the console was asked: " + q);
                },
                new PrintStream(outBytes, true),
                new PrintStream(errBytes, true),
                clock,
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                vars::get,
                "telegram");
    }

    /**
     * Runs one case, answers it from the phone, resumes, and returns the question the phone was
     * sent.
     */
    String phoneCase(String answer) {
        WeaveEnv env = env();
        String name = "p" + (++runs);
        int code =
                WeaveCLI.run(
                        script,
                        null,
                        "Triage",
                        Map.of("topic", "cats"),
                        null,
                        null,
                        null,
                        null,
                        dir.resolve("runs").resolve(name),
                        store,
                        false,
                        false,
                        null,
                        null,
                        null,
                        env);
        assertThat(code).as(errBytes.toString()).isEqualTo(4);
        String question = telegram.last().text();
        String id = new PendingStore(store).open().get(0).code();
        telegram.reply(ChannelHarness.ME, id + " " + answer);
        assertThat(WeaveCLI.tick(store, env)).isZero();
        assertThat(outBytes.toString()).contains("Workflow completed successfully");
        return question;
    }

    List<LCase> cases() {
        return LedgerFold.cases(new FileLedger(store.resolve("autonomy")).records("Publish"));
    }

    @Test
    void
            theBlindRungAsksForThePersonsOwnWorkAndNothingSentShowsTheDraftThenTheConfirmRungShowsItAndTakesAcceptRejectOrACorrection() {
        // blind: the first two cases
        String q1 = phoneCase("my own words about cats");
        String q2 = phoneCase("another of my own");
        assertThat(q1)
                .contains("editors, please write the post for Publish")
                .contains("topic = cats")
                .doesNotContain("SECRET")
                .doesNotContain("proposes");
        assertThat(q2).doesNotContain("SECRET");
        assertThat(telegram.sent)
                .as("question and confirmation of both: none shows the draft or its reasoning")
                .allSatisfy(s -> assertThat(s.text()).doesNotContain("SECRET"));
        assertThat(cases()).extracting(LCase::blind).containsExactly(true, true);
        assertThat(cases())
                .extracting(LCase::responseKind)
                .containsExactly(
                        "edit",
                        "edit"); // their own text is not the agent's: an edit with the final text

        // confirm: shown the draft, and the three kinds of answer
        String q3 = phoneCase("accept");
        assertThat(q3)
                .contains("review the post for Publish")
                .contains("The agent proposes:")
                .contains(DRAFT);
        phoneCase("reject");
        phoneCase("the corrected post");
        List<LCase> all = cases();
        assertThat(all)
                .extracting(LCase::responseKind)
                .containsExactly("edit", "edit", "accept", "reject", "edit");
        assertThat(all.get(2).shown()).isTrue();
        assertThat(all.get(2).level()).isEqualTo("edit");
        assertThat(all.get(4).responseFinal()).isEqualTo("the corrected post");
        assertThat(all.get(3).responseFinal()).as("a reject leaves nothing").isNullOrEmpty();
        assertThat(all).allSatisfy(c -> assertThat(c.responder()).isEqualTo("editors"));
    }
}
