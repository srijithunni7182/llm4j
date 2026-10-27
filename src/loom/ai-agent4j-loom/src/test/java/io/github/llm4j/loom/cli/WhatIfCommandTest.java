package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R25, R31, R32 of spec 13 through the command. */
class WhatIfCommandTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    static WhyCommandTest.Out whatif(String... args) {
        return whatifWith(
                m -> {
                    throw new IllegalStateException("weave whatif calls no model");
                },
                args);
    }

    static WhyCommandTest.Out whatifWith(
            io.github.llm4j.loom.execution.LLMClientFactory models, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        models,
                        q -> {
                            throw new IllegalStateException("weave whatif asks no one");
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
        WhatIfCommand c = new WhatIfCommand();
        new CommandLine(c).parseArgs(args);
        int code = WhatIfCommand.whatif(c, env);
        return new WhyCommandTest.Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path runDirOf(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        return TravelFixtures.runDir(dir, TravelFixtures.run(dir, c));
    }

    static final String FLIP = "{\"kind\":\"refund\",\"urgent\":\"yes\"}";

    @Test
    void showsHowTheRunWouldHaveGoneAndTheOriginalIsNotTouched() throws Exception {
        Path run = runDirOf("triage order status");
        String before = WhyCommandTest.hashOf(run);
        var o = whatif(run.toString(), "--at", "8", "--answer", "Intent=" + FLIP);
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("What if, at classify Intent")
                .contains("Intent answers " + FLIP)
                .contains("Candidate: completed")
                .contains("result \"retention\"")
                .contains(
                        "They part: alt (intent.urgent == \"yes\") went else in the original and then in the candidate")
                .contains("Only in the original");
        assertThat(WhyCommandTest.hashOf(run)).isEqualTo(before);
    }

    @Test
    void jsonCarriesTheSameFindings() throws Exception {
        Path run = runDirOf("triage order status");
        JsonNode j =
                JSON.readTree(
                        whatif(
                                        run.toString(),
                                        "--at",
                                        "Main/s0",
                                        "--answer",
                                        "Intent=" + FLIP,
                                        "--format",
                                        "json")
                                .out());
        assertThat(j.path("version").asInt()).isEqualTo(1);
        assertThat(j.path("candidateStatus").asText()).isEqualTo("completed");
        assertThat(j.path("result").path("candidate").asText()).isEqualTo("retention");
        assertThat(j.path("diff").path("onlyOriginal")).isNotEmpty();
        assertThat(j.path("changes").get(0).path("kind").asText()).isEqualTo("answer");
        assertThat(j.path("effects").path("unsafe").asBoolean()).isFalse();
    }

    @Test
    void whenAModelWouldBeNeededItSaysSoAndExitsNormally() throws Exception {
        Path run = runDirOf("triage order status");
        var o = whatif(run.toString(), "--at", "8", "--set", "message=where is my REFUND?");
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("The candidate needs a classifier answer from Intent")
                .contains("task text changed");
    }

    @Test
    void theCandidateCanBeSavedAndExplainedWithWhy() throws Exception {
        Path run = runDirOf("triage order status");
        Path saved = dir.resolve("candidate");
        var o =
                whatif(
                        run.toString(),
                        "--at",
                        "8",
                        "--answer",
                        "Intent=" + FLIP,
                        "--save",
                        saved.toString());
        assertThat(o.code()).isZero();
        assertThat(o.err()).contains("Saved the candidate");
        var why = WhyCommandTest.why(saved.toString());
        assertThat(why.code()).isZero();
        assertThat(why.out()).contains("→ TRUE  → then").contains("intent.urgent = \"yes\"");
        assertThat(why.out()).doesNotContain("Diverged");
    }

    @Test
    void aSavedCandidateWithASetVariableExplainsItselfWithThatValue() throws Exception {
        Path run = runDirOf("human answer");
        Path saved = dir.resolve("candidate-set");
        // the approval is set to "no" instead of asked
        var o = whatif(run.toString(), "--at", "4", "--human", "no", "--save", saved.toString());
        assertThat(o.code()).isZero();
        var why = WhyCommandTest.why(saved.toString());
        assertThat(why.out()).contains("approval = \"no\"").contains("→ false  → else");
        var set = runDirOf("triage order status");
        Path saved2 = dir.resolve("candidate-set2");
        whatif(set.toString(), "--at", "13", "--set", "intent=forced", "--save", saved2.toString());
        // a variable set from outside is said to come from the what-if
        assertThat(WhyCommandTest.why(saved2.toString(), "--all").out()).isNotEmpty();
    }

    @Test
    void aSaveIntoAnExistingRunIsRefused() throws Exception {
        Path run = runDirOf("triage order status");
        var o =
                whatif(
                        run.toString(),
                        "--at",
                        "8",
                        "--answer",
                        "Intent=" + FLIP,
                        "--save",
                        run.toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("already holds a run");
    }

    @Test
    void anEditedScriptAndLiveModelsUnderACapRunForwardAndSayWhatTheySpent() throws Exception {
        Path run = runDirOf("triage order status");
        Path edited = dir.resolve("edited.loom");
        Files.writeString(
                edited,
                Files.readString(dir.resolve("triage-order-status.loom"))
                        .replace("\"reply\"", "\"reply politely\""));
        var fake = new io.github.llm4j.loom.whatif.WhatIfCandidateTest.FakeModels();
        var o =
                whatifWith(
                        fake,
                        run.toString(),
                        "--at",
                        "8",
                        "--script",
                        edited.toString(),
                        "--models",
                        "live",
                        "--max-tokens",
                        "1000",
                        "--yes");
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("Candidate: completed")
                .contains("result \"a live reply\"")
                .contains("the candidate differs from the one that ran")
                .contains("Live models made 1 calls (15 tokens)");
        assertThat(fake.models).hasSize(1);
        assertThat(fake.systems.get(0)).contains("reply politely");
    }

    @Test
    void aPromptFileAndAModelForTheCandidateCostNothingUntilAModelIsNeeded() throws Exception {
        Path run = runDirOf("triage order status");
        Path prompt = dir.resolve("rhyme.md");
        Files.writeString(prompt, "Reply in rhyme.");
        var o =
                whatif(
                        run.toString(),
                        "--at",
                        "8",
                        "--prompt",
                        "Replier=" + prompt,
                        "--model",
                        "Replier=other");
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("Candidate: needs").contains("model or prompt changed");
        assertThat(whatif(run.toString(), "--at", "8", "--prompt", "Replier=/nowhere.md").err())
                .contains("cannot read");
        assertThat(whatif(run.toString(), "--at", "8", "--script", "/nowhere.loom").err())
                .contains("is not there");
    }

    @Test
    void realEffectsLiveModelsAndBadInputAreRefusedWithAReason() throws Exception {
        Path run = runDirOf("triage order status");
        assertThat(whatif(run.toString(), "--at", "8", "--effects", "real").err())
                .contains("never performs effects");
        assertThat(whatif(run.toString(), "--at", "8", "--models", "live").err())
                .contains("spends money");
        assertThat(
                        whatif(run.toString(), "--at", "8", "--models", "live", "--max-tokens", "9")
                                .err())
                .contains("Add --yes");
        assertThat(
                        whatif(
                                        run.toString(),
                                        "--at",
                                        "8",
                                        "--models",
                                        "live",
                                        "--max-cost",
                                        "1",
                                        "--yes")
                                .err())
                .contains("--prices");
        assertThat(whatif(run.toString(), "--at", "8", "--format", "xml").code()).isEqualTo(2);
        assertThat(whatif(run.toString(), "--at", "99").err()).contains("never ran in this run");
        assertThat(
                        whatif(
                                        run.toString(),
                                        "--at",
                                        "8",
                                        "--answer",
                                        "Intent={\"kind\":\"zzz\",\"urgent\":\"no\"}")
                                .err())
                .contains("can be order_status, refund, other, not zzz");
        assertThat(whatif(run.toString(), "--at", "8", "--fail", "Replier=rate_limit:x").err())
                .contains("Target=category");
    }

    @Test
    void aChangedScriptIsRefused() throws Exception {
        var c = TravelFixtures.CORPUS.get(1);
        var real = TravelFixtures.run(dir, c);
        Path run = TravelFixtures.runDir(dir, real);
        Files.writeString(real.script(), Files.readString(real.script()) + "\n// edited\n");
        var o = whatif(run.toString(), "--at", "8", "--answer", "Intent=" + FLIP);
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("has changed since this run started");
    }

    @Test
    void theSameWhatIfTwiceIsTheSameBytes() throws Exception {
        Path run = runDirOf("triage order status");
        for (String format : List.of("text", "md", "json"))
            assertThat(
                            whatif(
                                            run.toString(),
                                            "--at",
                                            "8",
                                            "--answer",
                                            "Intent=" + FLIP,
                                            "--format",
                                            format)
                                    .out())
                    .isEqualTo(
                            whatif(
                                            run.toString(),
                                            "--at",
                                            "8",
                                            "--answer",
                                            "Intent=" + FLIP,
                                            "--format",
                                            format)
                                    .out());
    }
}
