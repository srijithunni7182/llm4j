package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code weave eval}: R1 to R6 of loom-weave-eval, through the command. */
class EvalCommandTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final AtomicInteger clients = new AtomicInteger();
    final List<String> modelsAsked = Collections.synchronizedList(new ArrayList<>());
    final List<String> everythingSent = Collections.synchronizedList(new ArrayList<>());
    String answerToTheQuestion = "yes";
    BiFunction<String, String, String> reply = (model, system) -> "```json\n{\"thought\": \"t\", \"final_answer\": \"The answer is 36.\"}\n```";

    static final String SCRIPT = """
            agent Helper { model: "m-help" system: "You help." }
            agent Checker { model: "m-check" system: "You check." }
            workflow Main(topic) {
                delegate "Research {topic}" to Helper -> findings
                note "done"
            }
            """;

    EvalCommand command(String... args) {
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(args);
        return c;
    }

    Path script() throws Exception {
        return Files.writeString(dir.resolve("main.loom"), SCRIPT);
    }

    void dataset(String file, String yaml) throws Exception {
        Path d = dir.resolve("eval/golden");
        Files.createDirectories(d);
        Files.writeString(d.resolve(file), yaml);
    }

    WeaveEnv env() {
        LLMClientFactory models = model -> {
            clients.incrementAndGet();
            modelsAsked.add(model);
            return new LLMClient() {
                @Override public LLMResponse chat(LLMRequest request) {
                    everythingSent.add(request.getMessages().stream().map(m -> String.valueOf(m.getContent())).collect(java.util.stream.Collectors.joining("\n")));
                    return LLMResponse.builder().content(reply.apply(model, request.getMessages().get(0).getContent())).model(model).tokenUsage(10, 5, 15).build();
                }
                @Override public Stream<LLMResponse> chatStream(LLMRequest request) { return Stream.of(chat(request)); }
            };
        };
        PrintStream o = new PrintStream(out, true);
        return new WeaveEnv(models, m -> answerToTheQuestion, o, new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> "key");
    }

    int run(String... args) throws Exception {
        List<String> all = new ArrayList<>();
        all.add(script().toString());
        all.addAll(List.of(args));
        return EvalCommand.eval(command(all.toArray(String[]::new)), env());
    }

    String output() {
        return out.toString() + err;
    }

    // ── --init ───────────────────────────────────────────────────────────────

    @Test
    void r4_5_initCreatesAStarterDatasetAndNeverOverwritesAFile() throws Exception {
        assertThat(run("--init")).isZero();

        Path golden = dir.resolve("eval/golden");
        assertThat(golden.resolve("helper.yaml")).exists();
        assertThat(golden.resolve("checker.yaml")).exists();
        assertThat(golden.resolve("workflow.yaml")).exists();
        assertThat(golden.resolve("dataset.yaml")).exists();
        assertThat(out.toString()).contains("created").contains("--check");

        Files.writeString(golden.resolve("helper.yaml"), "- name: mine\n  input: keep me\n");
        out.reset();
        assertThat(run("--init")).isZero();
        assertThat(Files.readString(golden.resolve("helper.yaml"))).contains("keep me");
        assertThat(out.toString()).contains("kept").contains("helper.yaml");
    }

    @Test
    void r4_5_theStarterDatasetChecksCleanAndRunsInAMockRunWithNoModel() throws Exception {
        run("--init");
        out.reset();

        assertThat(run("--check")).isZero();
        assertThat(out.toString()).contains("✓ golden: 3 scenarios in 3 files");
        out.reset();
        assertThat(run("--mock")).isZero();
        assertThat(clients.get()).isZero();
    }

    // ── --check ──────────────────────────────────────────────────────────────

    @Test
    void r3_8_checkReportsWhatIsWrongWithoutCallingAModel() throws Exception {
        dataset("helpr.yaml", "- name: n\n  input: x\n");
        dataset("checker.yaml", "- name: n\n  input: \"\"\n");

        assertThat(run("--check")).isEqualTo(2);

        assertThat(out.toString()).contains("✗ helpr.yaml: the script has no agent or workflow named helpr; did you mean Helper?")
                .contains("checker.yaml [n]: has no input").contains("2 problems in");
        assertThat(clients.get()).isZero();
    }

    @Test
    void aMissingFolderSaysHowToCreateOne() throws Exception {
        assertThat(run("--check")).isEqualTo(2);

        assertThat(err.toString()).contains("there is no dataset folder at").contains("--init");
    }

    @Test
    void aFolderWithNoDatasetFilesIsAProblemToo() throws Exception {
        Files.createDirectories(dir.resolve("eval/golden"));

        assertThat(run("--check")).isEqualTo(2);
        assertThat(out.toString()).contains("has no dataset files").contains("helper.yaml");
    }

    @Test
    void checkAndInitTogetherAreRefused() throws Exception {
        assertThat(run("--check", "--init")).isEqualTo(2);
        assertThat(err.toString()).contains("cannot be used together");
    }

    @Test
    void aBadFixturesFileIsReportedByCheck() throws Exception {
        dataset("helper.yaml", "- name: n\n  input: x\n");
        dataset("fixtures.yaml", "Search: nope\n");

        assertThat(run("--check")).isEqualTo(2);
        assertThat(out.toString()).contains("Search should be a list of entries");
    }

    // ── a mock run ───────────────────────────────────────────────────────────

    @Test
    void r3_5_aMockRunNeverTouchesTheRealModelsAndJudgesNothing() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\", rubric: [Is right]}\n");
        dataset("workflow.yaml", "- {id: w-1, name: b, input: t, expect: [It ran]}\n");

        int code = run("--mock");

        assertThat(code).isZero();
        assertThat(clients.get()).isZero();
        assertThat(out.toString()).contains("🧪 Mock evaluation of main.loom: 2 scenarios").contains("? Helper · h-1").contains("? Main · w-1")
                .contains("0 passed, 0 failed, 2 unjudged").contains("mock run, nothing was spent").contains("a mock run does not check content");
    }

    @Test
    void theShippedNewsletterExampleRunsInAMockRun() throws Exception {
        File script = Path.of("../../examples/newsletter/main.loom").toFile();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        WeaveEnv e = env();
        e = new WeaveEnv(e.models(), e.human(), new PrintStream(o, true), e.err(), e.clock(), e.sleeper(), e.commands(), e.weave(), k -> "key");

        int code = EvalCommand.eval(command(script.toString(), "--mock"), e);

        assertThat(code).isZero();
        assertThat(o.toString()).contains("5 scenarios").contains("0 failed").contains("5 unjudged");
        assertThat(clients.get()).isZero();
    }

    @Test
    void aMockRunStillFailsWhenTheWiringIsBroken() throws Exception {
        dataset("workflow.yaml", "- {id: w-1, name: b, input: t}\n");
        Files.writeString(dir.resolve("main.loom"), "agent Helper { model: \"m\" system: \"s\" }\nworkflow Main(topic) { delegate \"x\" to Ghost -> r }\n");

        int code = EvalCommand.eval(command(dir.resolve("main.loom").toString(), "--mock"), env());

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("✗ Main · w-1").contains("error:");
    }

    // ── a real run ───────────────────────────────────────────────────────────

    @Test
    void aRealRunSaysWhoGradesAndWithWhichKeyBeforeAskingToSpend() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, rubric: [Is right]}\n");
        answerToTheQuestion = "no";

        run();
        assertThat(out.toString()).contains("Judge: m-help").contains("grades its own answers").contains("--judge <model> names another");

        out.reset();
        answerToTheQuestion = "no";
        run("--judge", "claude-haiku-4-5-20251001");
        assertThat(out.toString()).contains("Judge: claude-haiku-4-5-20251001 using ANTHROPIC_API_KEY").doesNotContain("grades its own answers");
    }

    @Test
    void r6_1_aRealRunSaysWhatItWillDoAndAsksFirst_andNoMeansNothingRuns() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\", rubric: [Is right]}\n");
        answerToTheQuestion = "no";

        int code = run();

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).contains("This will make real model calls: 1 scenario, at least one agent call each, and up to 1 judge call").contains("Cancelled; nothing was run.");
        assertThat(clients.get()).isZero();
    }

    @Test
    void aRealRunWithYesRunsChecksAndExitsZeroWhenAllPass() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\"}\n");

        int code = run("--yes");

        assertThat(code).isZero();
        assertThat(out.toString()).contains("✓ Helper · h-1").contains("1 passed, 0 failed, 0 unjudged").doesNotContain("Cancelled");
        assertThat(clients.get()).isPositive();
    }

    @Test
    void r3_6_aFailedCheckExitsOneAndTheDetailSaysWhy() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"37\"}\n");

        int code = run("--yes");

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("✗ Helper · h-1").contains("✗ answer contains: 37").contains("the answer was: The answer is 36.").contains("0 passed, 1 failed");
    }

    @Test
    void r3_4_rubricLinesAreJudgedByTheAgentsOwnModelUnlessAJudgeIsNamed() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, rubric: [Is right]}\n");
        reply = (model, system) -> system.contains("You help.")
                ? "```json\n{\"thought\": \"t\", \"final_answer\": \"The answer.\"}\n```"
                : "```json\n{\"rating\": 5, \"reasoning\": \"fully right\"}\n```";

        assertThat(run("--yes")).isZero();
        assertThat(modelsAsked).contains("m-help");
        assertThat(out.toString()).contains("1 passed, 0 failed, 0 unjudged");

        modelsAsked.clear();
        out.reset();
        reply = (model, system) -> model.equals("the-judge")
                ? "```json\n{\"rating\": 2, \"reasoning\": \"wrong\"}\n```"
                : "```json\n{\"thought\": \"t\", \"final_answer\": \"The answer.\"}\n```";

        assertThat(run("--yes", "--judge", "the-judge")).isEqualTo(1);
        assertThat(modelsAsked).contains("the-judge");
        assertThat(out.toString()).contains("✗ rubric: Is right").contains("wrong");
    }

    @Test
    void aJudgeThatCannotBeCreatedLeavesLinesUnjudgedAndSaysSo() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, rubric: [Is right]}\n");
        LLMClientFactory noJudge = model -> {
            if (model.equals("nobody")) throw new IllegalStateException("no key for nobody");
            return env().models().createClient(model);
        };
        PrintStream o = new PrintStream(out, true);
        WeaveEnv e = new WeaveEnv(noJudge, m -> "yes", o, o, Clock.systemUTC(), d -> { }, c -> null, List.of("weave"), k -> "key");

        int code = EvalCommand.eval(command(script().toString(), "--yes", "--judge", "nobody"), e);

        assertThat(code).isZero();
        assertThat(out.toString()).contains("? no judge: no key for nobody").contains("0 passed, 0 failed, 1 unjudged");
    }

    @Test
    void r3_5_withNoLimitGivenARealRunSaysWhichCapApplies() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\"}\n");

        run("--yes");
        assertThat(out.toString()).contains("No limit was given, so a real run is capped at 500000 tokens");

        out.reset();
        run("--yes", "--max-tokens", "100000");
        assertThat(out.toString()).doesNotContain("No limit was given");
    }

    @Test
    void r3_5_aLimitThatIsReachedStopsTheRunAndCountsWhatWasNotRun() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\"}\n- {id: h-2, name: b, input: q, expected_output_contains: \"36\"}\n- {id: h-3, name: c, input: q, expected_output_contains: \"36\"}\n");

        int code = run("--yes", "--max-tokens", "10");

        assertThat(out.toString()).contains("Stopped early: a limit was reached").contains("not run");
        assertThat(out.toString()).doesNotContain("✓ Helper · h-3").doesNotContain("✗ Helper · h-3");
        assertThat(code).isIn(0, 1);
    }

    @Test
    void theOptionsForLimitsAreValidated() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\"}\n");

        assertThat(run("--yes", "--max-cost", "0.50")).isEqualTo(2);
        assertThat(err.toString()).contains("--max-cost needs --prices");
        err.reset();
        Path prices = Files.writeString(dir.resolve("prices.properties"), "m-help = 1 / 2\n");
        assertThat(run("--yes", "--max-cost", "lots", "--prices", prices.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("--max-cost takes a number");
        err.reset();
        assertThat(run("--yes", "--max-tokens", "0")).isEqualTo(2);
        assertThat(err.toString()).contains("budget limits must be positive");
    }

    // ── what is run ──────────────────────────────────────────────────────────

    @Test
    void agentAndWorkflowFiltersPickWhatToRunAndNothingMatchingIsAnError() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q}\n");
        dataset("checker.yaml", "- {id: c-1, name: b, input: q}\n");
        dataset("workflow.yaml", "- {id: w-1, name: c, input: t}\n");

        assertThat(run("--mock", "--agent", "checker")).isZero();
        assertThat(out.toString()).contains("1 scenario").contains("Checker · c-1").doesNotContain("Helper · h-1");
        out.reset();
        assertThat(run("--mock", "--workflow", "Main")).isZero();
        assertThat(out.toString()).contains("Main · w-1").doesNotContain("Helper");

        out.reset();
        assertThat(run("--mock", "--agent", "Nobody")).isEqualTo(2);
        assertThat(err.toString()).contains("no scenarios match --agent Nobody").contains("The dataset covers: Checker, Helper, Main");
    }

    @Test
    void theInputOfAWorkflowScenarioGoesToItsFirstParameter() throws Exception {
        dataset("workflow.yaml", "- {id: w-1, name: c, input: \"home composting\", expected_output_contains: \"36\"}\n");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        reply = (model, system) -> "```json\n{\"thought\": \"t\", \"final_answer\": \"The answer is 36.\"}\n```";
        PrintStream o = new PrintStream(out, true);
        LLMClientFactory spy = model -> new LLMClient() {
            @Override public LLMResponse chat(LLMRequest request) {
                request.getMessages().forEach(m -> seen.add(m.getContent()));
                return LLMResponse.builder().content(reply.apply(model, "")).model(model).tokenUsage(1, 1, 2).build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest request) { return Stream.of(chat(request)); }
        };

        int code = EvalCommand.eval(command(script().toString(), "--yes"), new WeaveEnv(spy, m -> "yes", o, o, Clock.systemUTC(), d -> { }, c -> null, List.of("weave"), k -> "key"));

        assertThat(code).isZero();
        assertThat(String.join("\n", seen)).contains("Research home composting");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    @Test
    void r3_9_aToolWithFixturesAnswersFromThemInARealRunWithoutReachingTheOutsideWorld() throws Exception {
        Files.writeString(dir.resolve("main.loom"), """
                tool Search { use: serpapi  api_key: env.SERPAPI_KEY }
                agent Helper { model: "m-help" system: "You help." tools: [Search] }
                workflow Main(topic) { delegate "go" to Helper -> r }
                """);
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_tools: [Search], expected_output_contains: \"sunny\"}\n");
        dataset("fixtures.yaml", "Search:\n  - match: .*\n    snippets: [\"Paris is sunny\"]\n");
        List<String> observations = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger n = new AtomicInteger();
        reply = (model, system) -> {
            observations.add(system);
            return n.getAndIncrement() == 0 ? "```json\n{\"thought\": \"look\", \"action\": \"Search\", \"action_input\": {\"query\": \"paris\"}}\n```"
                    : "```json\n{\"thought\": \"t\", \"final_answer\": \"It is sunny.\"}\n```";
        };

        int code = EvalCommand.eval(command(dir.resolve("main.loom").toString(), "--yes"), env());

        assertThat(code).as(output()).isZero();
        assertThat(out.toString()).contains("✓ Helper · h-1");
    }

    // ── reports ──────────────────────────────────────────────────────────────

    @Test
    void jsonAndReportFilesAreWrittenAndCarryTheThreeCounts() throws Exception {
        dataset("helper.yaml", "- {id: h-1, name: a, input: q, expected_output_contains: \"36\", rubric: [Is right]}\n");
        Path json = dir.resolve("out/results.json");
        Path html = dir.resolve("out/report.html");
        Files.createDirectories(json.getParent());

        int code = run("--yes", "--json", json.toString(), "--report", html.toString());

        assertThat(code).isZero();
        assertThat(Files.readString(json)).contains("\"passed\" : 0").contains("\"unjudged\" : 1").contains("\"status\" : \"unjudged\"");
        assertThat(Files.readString(html)).contains("<b>0</b> passed").contains("<b>1</b> unjudged").doesNotContain("<script");
    }

    // ── R4.1: nothing else needs a dataset ───────────────────────────────────

    @Test
    void r4_1_checkAndRunNeverNeedADatasetOrAnEvalFolder() throws Exception {
        Path s = script();
        assertThat(dir.resolve("eval")).doesNotExist();

        assertThat(WeaveCLI.check(s.toFile(), null, false, env())).isZero();
        assertThat(WeaveCLI.run(s.toFile(), null, "Main", java.util.Map.of("topic", "x"), null, null, null, null, null, null, false, env())).isZero();

        assertThat(dir.resolve("eval")).doesNotExist();
    }

    // ── workflows with several parameters ───────────────────────────────────────────────

    static final String TWO_PARAMETERS = """
            agent Writer { model: "m-help" system: "You write." }
            workflow Main(topic, platform) {
                delegate "Write about {topic} for {platform}" to Writer -> post
            }
            """;

    int runTwoParameters(String yaml, String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), TWO_PARAMETERS);
        dataset("workflow.yaml", yaml);
        List<String> all = new ArrayList<>();
        all.add(dir.resolve("main.loom").toString());
        all.addAll(List.of(args));
        return EvalCommand.eval(command(all.toArray(String[]::new)), env());
    }

    @Test
    void aWorkflowWithSeveralParametersGetsEachOneByNameAndTheTaskCarriesThemAll() throws Exception {
        reply = (model, system) -> "```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```";
        String yaml = """
                - id: w-1
                  inputs: { topic: pricing, platform: linkedin }
                  expect: ["it wrote a post"]
                """;
        assertThat(runTwoParameters(yaml, "--check")).isZero();
        assertThat(runTwoParameters(yaml, "--mock")).isZero();
        out.reset();
        assertThat(runTwoParameters(yaml, "--yes", "--max-tokens", "100000")).isNotEqualTo(2);
        assertThat(String.join("\n", everythingSent)).contains("Write about pricing for linkedin");
    }

    @Test
    void inputIsTheFirstParameterAndInputsGivesTheRest() throws Exception {
        String yaml = """
                - id: w-1
                  input: pricing
                  inputs: { platform: linkedin }
                """;
        assertThat(runTwoParameters(yaml, "--check")).isZero();
    }

    @Test
    void aParameterNobodySuppliesAnUnknownNameAndAClashAreSaidBeforeAnythingRuns() throws Exception {
        assertThat(runTwoParameters("- id: w-1\n  input: pricing\n", "--check")).isEqualTo(2);
        assertThat(output()).contains("workflow Main needs platform").contains("inputs: { platform: ... }");

        out.reset();
        err.reset();
        assertThat(runTwoParameters("- id: w-1\n  inputs: { topic: a, platform: b, channel: c }\n", "--check")).isEqualTo(2);
        assertThat(output()).contains("inputs.channel: workflow Main has no parameter channel").contains("it has topic, platform");

        out.reset();
        err.reset();
        assertThat(runTwoParameters("- id: w-1\n  input: x\n  inputs: { topic: a, platform: b }\n", "--check")).isEqualTo(2);
        assertThat(output()).contains("both input: and inputs.topic");
    }

    @Test
    void aMappingWhereTextIsExpectedIsExplainedNotShownAsAParserException() throws Exception {
        assertThat(runTwoParameters("- id: w-1\n  input: { topic: a, platform: b }\n", "--check")).isEqualTo(2);
        assertThat(output()).contains("input: must be text").contains("inputs: { name: value, ... }").doesNotContain("Cannot deserialize").doesNotContain("JsonToken");

        out.reset();
        err.reset();
        assertThat(runTwoParameters("- id: w-1\n  inputs: linkedin\n", "--check")).isEqualTo(2);
        assertThat(output()).contains("inputs: must be a mapping");
    }

    @Test
    void anAgentScenarioTakesTextOnly() throws Exception {
        Files.writeString(dir.resolve("main.loom"), TWO_PARAMETERS);
        dataset("writer.yaml", "- id: a-1\n  input: hi\n  inputs: { topic: a }\n");
        int code = EvalCommand.eval(command(dir.resolve("main.loom").toString(), "--check"), env());
        assertThat(code).isEqualTo(2);
        assertThat(output()).contains("inputs: is for a workflow's parameters");
    }

    // ── a fixed check that a word never appears ───────────────────────────────

    @Test
    void anAnswerThatMustNeverContainSomethingFailsWithoutRepeatingTheAnswer() throws Exception {
        dataset("helper.yaml", "- id: h-1\n  input: q\n  expected_output_not_contains: [\"4111\"]\n- id: h-2\n  input: q\n  expected_output_not_contains: \"nothing-like-this\"\n");
        reply = (model, system) -> "```json\n{\"thought\": \"t\", \"final_answer\": \"Your card 4111 is fine.\"}\n```";

        int code = run("--yes", "--max-tokens", "100000", "--agent", "Helper");

        assertThat(code).isEqualTo(1);
        String text = output();
        assertThat(text).contains("answer never contains").contains("4111").contains("the answer contains it");
        assertThat(text).as("the failure does not echo the answer it is about").doesNotContain("Your card 4111 is fine");
    }

    @Test
    void inAMockRunTheNeverContainsCheckIsNotJudgedBecauseTheAnswerIsFixed() throws Exception {
        dataset("helper.yaml", "- id: h-1\n  input: q\n  expected_output_not_contains: [\"4111\"]\n");
        assertThat(run("--mock", "--agent", "Helper")).isZero();
        assertThat(output()).contains("mock run").doesNotContain("PASS");
    }

    @Test
    void aShortAnswerFailsTheMinimumWordsCheckAndSaysHowShortItWas() throws Exception {
        dataset("helper.yaml", "- id: h-1\n  input: q\n  expected_min_words: 500\n");
        reply = (model, system) -> "```json\n{\"thought\": \"t\", \"final_answer\": \"only five words in here\"}\n```";

        int code = run("--yes", "--max-tokens", "100000", "--agent", "Helper");

        assertThat(code).isEqualTo(1);
        assertThat(output()).contains("answer has at least").contains("500 words").contains("the answer has 5 words");
    }

    @Test
    void inAMockRunTheWordCountIsNotJudged() throws Exception {
        dataset("helper.yaml", "- id: h-1\n  input: q\n  expected_min_words: 500\n");
        assertThat(run("--mock", "--agent", "Helper")).isZero();
        assertThat(output()).contains("mock run").doesNotContain("PASS");
    }
}
