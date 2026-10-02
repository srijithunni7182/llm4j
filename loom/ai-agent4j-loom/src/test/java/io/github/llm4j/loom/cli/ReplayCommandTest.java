package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code weave replay} against runs written by {@code weave run}, and the same replay done by hand with {@code weave fork} (spec loom-earned-autonomy R7.1, R7.11). */
class ReplayCommandTest {

    @TempDir
    Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
    File script;
    File candidate;
    Path store;
    int modelCalls;
    int asked;
    private int runs;

    @BeforeEach
    void setUp() throws Exception {
        String source = io.github.llm4j.loom.autonomy.Scripts2Access.refund("").replace("agent Triager {", "agent Summarizer { model: \"m\" system: \"You are Summarizer.\" }\nagent Triager {")
                .replace("    decide Refund -> verdict", "    delegate \"Summarise {ticket}\" to Summarizer -> summary\n    decide Refund -> verdict");
        script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), source);
        candidate = dir.resolve("stricter.loom").toFile();
        Files.writeString(candidate.toPath(), source.replace("You are Triager.", "You are a stricter Triager."));
        store = dir.resolve("store");
    }

    WeaveEnv env() {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                modelCalls++;
                String system = request.getMessages().get(0).getContent();
                var messages = request.getMessages();
                String last = messages.get(messages.size() - 1).getContent();
                if (system.contains("You are Summarizer")) return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"a summary\"}\n```").model("m").tokenUsage(10, 5, 15).build();
                Map<String, String> f = AutonomyCommandsTest.fields(last);
                int amount = Integer.parseInt(f.getOrDefault("amount", "0"));
                String choice = system.contains("stricter") && amount >= 100 ? "reject" : "approve";
                return LLMResponse.builder().content("```json\n{\"choice\": \"" + choice + "\", \"reasoning\": \"by " + (system.contains("stricter") ? "the stricter rule" : "the old rule") + "\", \"confidence\": 0.9}\n```")
                        .model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        return new WeaveEnv(m -> client, question -> {
            asked++;
            return Integer.parseInt(AutonomyCommandsTest.fields(question).get("amount")) < 100 ? "approve" : "reject";
        }, new PrintStream(outBytes, true), new PrintStream(errBytes, true), clock, d -> { }, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"), System::getenv);
    }

    void runCases(int n) {
        for (int i = 0; i < n; i++) {
            Map<String, String> in = new LinkedHashMap<>();
            in.put("ticket", "T-" + i);
            in.put("tier", i % 2 == 0 ? "gold" : "basic");
            in.put("amount", String.valueOf(20 + i * 20));
            in.put("reason", "damaged");
            in.put("customer_since", "2020");
            WeaveCLI.run(script, null, "Triage", in, null, null, null, null, dir.resolve("runs").resolve("c" + (++runs)), store, false, false, null, null, env());
            clock.advance(Duration.ofHours(1));
        }
    }

    ReplayCommand command(File candidateFile) {
        ReplayCommand c = new ReplayCommand();
        c.script = script;
        c.candidate = candidateFile;
        c.decision = "Refund";
        c.store = store.toFile();
        c.seed = 1;
        c.repeat = 1;
        c.format = "md";
        return c;
    }

    @Test
    @Tag("EA-V7.12")
    @Tag("EA-V7.1")
    void replayReadsTheRunsTheirDirectoriesNameGradesTheCandidateAndKeepsItsOwnDirectory() throws Exception {
        runCases(12);
        int askedBefore = asked;
        String ledgerBefore = Files.readString(store.resolve("autonomy/Refund/ledger.jsonl"));
        int callsBefore = modelCalls;

        int code = ReplayCommand.replay(command(candidate), env());

        assertThat(code).as(errBytes.toString()).isZero();
        String report = outBytes.toString();
        assertThat(report).contains("# Replay of Refund").contains("12 cases selected, 12 replayed").contains("## Flips");
        assertThat(report.lines().filter(l -> l.startsWith("- ") && l.contains("incumbent approve, candidate reject")).count()).isEqualTo(8); // amounts 100..240 flip
        assertThat(modelCalls - callsBefore).as("one call per case: the summaries came from the journals").isEqualTo(12);
        assertThat(asked).isEqualTo(askedBefore);
        assertThat(Files.readString(store.resolve("autonomy/Refund/ledger.jsonl"))).as("the ledger is byte-identical").isEqualTo(ledgerBefore);
        try (var replays = Files.list(store.resolve("autonomy/Refund/replays"))) {
            Path only = replays.findFirst().orElseThrow();
            assertThat(Files.list(only).map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("log.jsonl", "plan.json", "report.json", "report.md");
        }
        assertThat(errBytes.toString()).contains("kept in");
    }

    @Test
    @Tag("EA-V7.15")
    void doingOneCaseByHandWithForkGivesTheSameProposalAsTheReplayEngine() throws Exception {
        runCases(6);
        outBytes.reset();
        assertThat(ReplayCommand.replay(command(candidate), env())).isZero();
        String report = outBytes.toString();
        // the case run c4 (amount 100) is one the candidate flips
        Path run = dir.resolve("runs/c5");
        Path child = dir.resolve("forked");
        outBytes.reset();
        int code = TravelCommands.fork(run, child, "Triage/s1", candidate, Map.of(), "simulate", "Triage/s1~2#decide-proposal", false, "by hand", true, env());
        assertThat(code).as(errBytes.toString()).isIn(0, 5);

        var forked = new io.github.llm4j.loom.runtime.FileRunJournal(child.resolve("journal.json"));
        Map<?, ?> proposal = (Map<?, ?>) forked.get("Triage/s1~2#decide-proposal").orElseThrow().value();
        assertThat(proposal.get("choice")).isEqualTo("reject");
        assertThat(report).contains("incumbent approve, candidate " + proposal.get("choice"));
        assertThat(new io.github.llm4j.loom.runtime.FileRunJournal(run.resolve("journal.json")).get("Triage/s1~2#decide-proposal")).as("the parent is untouched").isEmpty();
    }

    @Test
    @Tag("EA-V7.13")
    void aCandidateThatDoesNotLoadOrADecisionThatDoesNotExistExitsWithTwoAndSaysWhy() throws Exception {
        runCases(3);
        File broken = dir.resolve("broken.loom").toFile();
        Files.writeString(broken.toPath(), "decision Refund { frobnicate }");
        errBytes.reset();
        assertThat(ReplayCommand.replay(command(broken), env())).isEqualTo(2);
        assertThat(errBytes.toString()).startsWith("Error:");

        ReplayCommand missing = command(candidate);
        missing.decision = "Nope";
        errBytes.reset();
        assertThat(ReplayCommand.replay(missing, env())).isEqualTo(2);
        assertThat(errBytes.toString()).contains("has no decision named Nope");

        ReplayCommand nothing = command(null);
        nothing.script = null;
        errBytes.reset();
        assertThat(ReplayCommand.replay(nothing, env())).isEqualTo(2);
        assertThat(errBytes.toString()).contains("give the script");
    }

    @Test
    @Tag("EA-V7.8")
    void sinceAcceptsADurationOrADate() {
        var now = new MutableClock(Instant.parse("2026-03-10T12:00:00Z"));
        assertThat(ReplayCommand.parseSince("14d", now)).isEqualTo(Instant.parse("2026-02-24T12:00:00Z"));
        assertThat(ReplayCommand.parseSince("6h", now)).isEqualTo(Instant.parse("2026-03-10T06:00:00Z"));
        assertThat(ReplayCommand.parseSince("30m", now)).isEqualTo(Instant.parse("2026-03-10T11:30:00Z"));
        assertThat(ReplayCommand.parseSince("2026-03-01", now)).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(ReplayCommand.parseSince(null, now)).isNull();
    }
}
