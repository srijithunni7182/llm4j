package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.generate.GenerateFixtures;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.Writer;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R4, R32 of spec 12 through the command: the plan alone, a plan to build from, and the gates on
 * any project.
 */
class GenerateCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    static Out generate(io.github.llm4j.loom.execution.LLMClientFactory models, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        models,
                        q -> {
                            throw new IllegalStateException("weave generate asks no one here");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        GenerateCommand c = new GenerateCommand();
        new CommandLine(c).parseArgs(args);
        int code = GenerateCommand.generate(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static io.github.llm4j.loom.execution.LLMClientFactory noModels() {
        return m -> {
            throw new IllegalStateException("no real model in this test");
        };
    }

    static io.github.llm4j.loom.execution.LLMClientFactory saying(String reply) {
        return m ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest r) {
                        return LLMResponse.builder()
                                .content(reply)
                                .model(m)
                                .tokenUsage(100, 50, 150)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest r) {
                        return Stream.of(chat(r));
                    }
                };
    }

    private Path project() throws Exception {
        Path p = Files.createDirectory(dir.resolve("proj"));
        try (var ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            assertThat(ws.commit(p, false).ok()).isTrue();
        }
        return p;
    }

    @Test
    void gatesRunOnAProjectAndEachSaysPassOrWhatFailed() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("plan.json"), PlanJson.write(GenerateFixtures.plan()));
        Out o = generate(noModels(), "--gate-only", p.toString());
        assertThat(o.err()).isEmpty();
        assertThat(o.out())
                .contains("✓ G1 check")
                .contains("✓ G5 plan")
                .contains("All the gates that could run passed.");
        assertThat(o.code()).isZero();
    }

    @Test
    void aHandWrittenProjectWithoutAPlanRunsWhatCanRunAndSaysWhatWasSkipped() throws Exception {
        Out o = generate(noModels(), "--gate-only", project().toString());
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("✓ G1 check").contains("G5 (plan fidelity) was skipped");
    }

    @Test
    void aBrokenScriptFailsG1WithTheLineAndExitCode3() throws Exception {
        Path p = project();
        Files.writeString(
                p.resolve("main.loom"),
                Files.readString(p.resolve("main.loom")).replace("to Drafter", "to Nobody"));
        Out o = generate(noModels(), "--gate-only", p.toString());
        assertThat(o.code()).isEqualTo(3);
        assertThat(o.out()).contains("✗ G1 check").contains("Nobody");
    }

    @Test
    void jsonSaysTheSameThing() throws Exception {
        Path p = project();
        Files.writeString(
                p.resolve("main.loom"),
                Files.readString(p.resolve("main.loom")).replace("to Drafter", "to Nobody"));
        Out o = generate(noModels(), "--gate-only", p.toString(), "--format", "json");
        var json = new ObjectMapper().readTree(o.out());
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("passed").asBoolean()).isFalse();
        assertThat(json.get("gates").get(0).get("problems")).isNotEmpty();
    }

    @Test
    void aFolderWithoutAScriptIsNotAProject() throws Exception {
        Out o =
                generate(
                        noModels(),
                        "--gate-only",
                        Files.createDirectory(dir.resolve("none")).toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("has no main.loom");
    }

    @Test
    void thePlanAloneIsWrittenAsJsonAndMarkdownAndNothingElse() throws Exception {
        Path out = dir.resolve("plan-out");
        Out o =
                generate(
                        saying(PlanJson.write(GenerateFixtures.plan())),
                        "make a support notifier",
                        "--plan-only",
                        "--model",
                        "m",
                        "--out",
                        out.toString());
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("Plan written");
        assertThat(Files.list(out).map(f -> f.getFileName().toString()).sorted().toList())
                .containsExactly("PLAN.md", "plan.json");
        assertThat(PlanJson.read(Files.readString(out.resolve("plan.json"))))
                .isEqualTo(GenerateFixtures.plan());
        assertThat(Files.readString(out.resolve("PLAN.md"))).contains("# Plan: SupportNotify");
    }

    @Test
    void aPlanTheCheckerRejectsIsStillWrittenSoItCanBeEditedAndTheExitIs3() throws Exception {
        var p = GenerateFixtures.plan();
        var bad =
                new io.github.llm4j.loom.generate.Plan(
                        1,
                        p.name(),
                        p.summary(),
                        p.answers(),
                        p.agents(),
                        p.classifiers(),
                        p.tools(),
                        p.humans(),
                        List.of(),
                        p.budget(),
                        p.failure(),
                        p.tests(),
                        false);
        Path out = dir.resolve("bad-plan");
        Out o =
                generate(
                        saying(PlanJson.write(bad)),
                        "x",
                        "--plan-only",
                        "--model",
                        "m",
                        "--out",
                        out.toString());
        assertThat(o.code()).isEqualTo(3);
        assertThat(o.out()).contains("lists no effect").contains("edit it");
        assertThat(Files.exists(out.resolve("plan.json"))).isTrue();
    }

    @Test
    void recordingThenReplayingGivesTheSamePlanWithNoModel() throws Exception {
        Path rec = dir.resolve("t.jsonl");
        Path first = dir.resolve("first");
        generate(
                saying("noise " + PlanJson.write(GenerateFixtures.plan()) + " noise"),
                "make it",
                "--plan-only",
                "--model",
                "m",
                "--out",
                first.toString(),
                "--record",
                rec.toString());
        assertThat(Files.readString(rec)).doesNotContain("make it");
        Path second = dir.resolve("second");
        Out o =
                generate(
                        noModels(),
                        "make it",
                        "--plan-only",
                        "--replay",
                        rec.toString(),
                        "--out",
                        second.toString());
        assertThat(o.err()).isEmpty();
        assertThat(Files.readString(second.resolve("plan.json")))
                .isEqualTo(Files.readString(first.resolve("plan.json")));
    }

    @Test
    void aReplayOfAnotherDescriptionIsRefusedClearly() throws Exception {
        Path rec = dir.resolve("t.jsonl");
        generate(
                saying(PlanJson.write(GenerateFixtures.plan())),
                "make it",
                "--plan-only",
                "--model",
                "m",
                "--out",
                dir.resolve("a").toString(),
                "--record",
                rec.toString());
        Out o =
                generate(
                        noModels(),
                        "something else",
                        "--plan-only",
                        "--replay",
                        rec.toString(),
                        "--out",
                        dir.resolve("b").toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("is not what the transcript recorded");
    }

    @Test
    void fromPlanReadsAnEditedPlanAndChecksIt() throws Exception {
        Path planFile = dir.resolve("plan.json");
        Files.writeString(planFile, PlanJson.write(GenerateFixtures.plan()));
        Out o = generate(noModels(), "--from-plan", planFile.toString());
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("The plan is sound");
        Files.writeString(planFile, "{ nope");
        assertThat(generate(noModels(), "--from-plan", planFile.toString()).code()).isEqualTo(2);
    }

    @Test
    void usageErrorsExplain() {
        assertThat(generate(noModels()).code()).isEqualTo(1);
        assertThat(generate(noModels(), "x", "--format", "xml").code()).isEqualTo(1);
        Out noModel = generate(noModels(), "x", "--plan-only");
        assertThat(noModel.code()).isEqualTo(2);
        assertThat(noModel.err()).contains("LOOM_GENERATE_MODEL");
    }
}
