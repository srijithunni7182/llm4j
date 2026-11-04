package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Phase 25, R3: LP10's {@code split} option lets an agent that reasons answer in prose and adds an
 * extractor that pulls the old schema's fields out of it. It needs judgement, so it needs --yes.
 */
class SplitFixTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Deque<String> typed = new ArrayDeque<>();
    List<String> asked = new ArrayList<>();

    WeaveEnv env() {
        return new WeaveEnv(
                m -> null,
                q -> {
                    asked.add(q);
                    return typed.isEmpty() ? "" : typed.poll();
                },
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                k -> k.equals("HOOK") ? "https://example.com/hook" : null);
    }

    Path script() {
        return dir.resolve("w.loom");
    }

    int run(String source, String... args) throws Exception {
        Files.writeString(script(), source);
        return rerun(args);
    }

    int rerun(String... args) throws Exception {
        ReviewCommand c = new ReviewCommand();
        List<String> all = new ArrayList<>(List.of(script().toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        return ReviewCommand.review(c, env());
    }

    JsonNode finding(String source, String rule) throws Exception {
        out.reset();
        int code = run(source, "--format", "json", "--min-severity", "info", "--lens", "all");
        assertThat(code).as(err.toString()).isZero();
        for (JsonNode f : new ObjectMapper().readTree(out.toString()).get("findings"))
            if (f.get("rule").asText().equals(rule)) return f;
        throw new AssertionError("no " + rule);
    }


    static final String TRAPPED =
            """
            tool Files { use: file  root: "data"  mode: read }
            agent Analyst {
                model: "claude-sonnet-5-5"
                system: "You analyse the request."
                tools: [Files]
                output_schema: { summary: string, score: number, risks: list<{ name: string, level: number }> }
            }
            workflow Main(q) {
                delegate "Look at {q}" to Analyst -> report retry 1 timeout 30s
                alt (report.score < 5) { note "low: {report.summary}" } else { note "fine: {report.summary}" }
            }
            """;

    private String fingerprint(String source) throws Exception {
        return finding(source, "LP10").get("fingerprint").asText();
    }

    @Test
    void theFindingOffersTheSplitOption() throws Exception {
        JsonNode f = finding(TRAPPED, "LP10");

        List<String> ids = new ArrayList<>();
        f.get("options").forEach(o -> ids.add(o.get("id").asText()));
        assertThat(ids).containsExactly("split");
        assertThat(f.get("options").get(0).get("safety").asText()).isEqualTo("review");
    }

    @Test
    void aDryRunShowsTheWholeSplitAndWritesNothing() throws Exception {
        String fp = fingerprint(TRAPPED);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--dry-run")).isEqualTo(1);

        assertThat(out.toString())
                .contains("-    output_schema:")
                .contains("Answer in plain prose; explain your reasoning, then state your conclusions.")
                .contains("+extractor AnalystFields {")
                .contains("+    model: \"claude-haiku-5-5\"")
                .contains("+    instructions: \"Pull these fields out of the analysis.\"")
                .contains("+        summary: string")
                .contains("+        risks: list<{ name: string, level: number }>")
                .contains("-> report_analysis")
                .contains("+    extract AnalystFields from report_analysis -> report");
        assertThat(Files.readString(script())).isEqualTo(TRAPPED);
    }

    @Test
    void itNeedsYes() throws Exception {
        String fp = fingerprint(TRAPPED);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split")).isEqualTo(2);

        assertThat(err.toString()).contains("needs your judgement");
        assertThat(Files.readString(script())).isEqualTo(TRAPPED);
    }

    @Test
    void afterApplyingTheScriptLoadsLp10IsGoneAndNoHighFindingAppears() throws Exception {
        String fp = fingerprint(TRAPPED);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        io.github.llm4j.loom.ast.LoomScript parsed =
                new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
        assertThat(parsed.getExtractors()).extracting("name").containsExactly("AnalystFields");
        assertThat(parsed.getAgents().get(0).getOutputSchema()).isNull();
        assertThat(parsed.getAgents().get(0).getSystemPrompt()).endsWith("then state your conclusions.");
        assertThat(text).contains("extract AnalystFields from report_analysis -> report");
        // the field the branch reads stays required; the others may be missing
        assertThat(parsed.findExtractor("AnalystFields").getOptional()).containsExactlyInAnyOrder("summary", "risks");
        out.reset();
        assertThat(run(text, "--format", "json", "--min-severity", "info", "--lens", "all")).isZero();
        assertThat(out.toString()).doesNotContain("\"rule\" : \"LP10\"").doesNotContain("\"severity\" : \"high\"");
    }

    @Test
    void aStepThatCarriesTheSchemaAsExpectingLosesIt() throws Exception {
        String source =
                """
                tool Files { use: file  root: "data"  mode: read }
                agent Analyst { model: "claude-sonnet-5-5"  system: "You analyse the request."  tools: [Files] }
                workflow Main(q) {
                    delegate "Look at {q}" to Analyst -> report expecting { summary: string, detail: { a: string, b: number } }
                    note "{report.summary}"
                }
                """;
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        assertThat(text).doesNotContain("expecting").contains("-> report_analysis").contains("detail: { a: string, b: number }");
        assertThat(new io.github.llm4j.loom.execution.LoomLoader().load(script().toString()).getExtractors()).hasSize(1);
    }

    @Test
    void anUnknownProviderKeepsTheAgentsOwnModel() throws Exception {
        String source = TRAPPED.replace("claude-sonnet-5-5", "my-own-model");
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--dry-run")).isEqualTo(1);

        assertThat(out.toString()).contains("+    model: \"my-own-model\"");
    }

    @Test
    void theOptionNamesTheTestFilesThatScriptTheAgent() throws Exception {
        Files.createDirectories(dir.resolve("tests"));
        Files.writeString(
                dir.resolve("tests/w.test.yaml"),
                "script: ../w.loom\nworkflow: Main\ncases:\n  - name: c\n    models:\n      Analyst: '{\"summary\": \"x\"}'\n");
        JsonNode f = finding(TRAPPED, "LP10");

        assertThat(f.get("options").get(0).get("explain").asText()).contains("tests/w.test.yaml");
    }

    @Test
    void aLaterPromptThatWritesTheWholeAnswerStillWorksAfterTheSplit() throws Exception {
        String source = TRAPPED.replace("alt (report.score < 5)", "note \"all of it: {report}\"\n    alt (report.score < 5)");
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        // the extract step writes the same variable with the same fields, so {report} is still the record
        assertThat(Files.readString(script())).contains("note \"all of it: {report}\"").contains("-> report");
        assertThat(new io.github.llm4j.loom.execution.LoomLoader().load(script().toString()).getExtractors()).hasSize(1);
    }

    @Test
    void notOfferedForABroadcast() throws Exception {
        String source =
                TRAPPED.replace(
                        "delegate \"Look at {q}\" to Analyst -> report retry 1 timeout 30s",
                        "delegate \"Look at {q}\" to Analyst -> report retry 1 timeout 30s\n    broadcast \"again\" to [Analyst] -> others");
        JsonNode f = finding(source, "LP10");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("broadcast");
    }

    @Test
    void notOfferedWhenTheStepsDisagreeAboutTheSchema() throws Exception {
        String source =
                """
                tool Files { use: file  root: "data"  mode: read }
                agent Analyst { model: "m"  system: "You analyse the request."  tools: [Files] }
                workflow Main(q) {
                    delegate "one {q}" to Analyst -> a expecting { x: string, y: { z: string } }
                    delegate "two {q}" to Analyst -> b expecting { p: string, q: { r: string } }
                    note "{a.x} {b.p}"
                }
                """;
        JsonNode f = finding(source, "LP10");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("differ");
    }

    @Test
    void anAgentWrittenOnOneLineWithNoSystemPromptIsSplitToo() throws Exception {
        String source =
                """
                tool Files { use: file  root: "data"  mode: read }
                agent Analyst { model: "gemini-2.5-pro"  tools: [Files]  output_schema: { a: string, b: { c: string } } }
                workflow Main(q) {
                    delegate "Look at {q}" to Analyst -> report
                    note "{report.a}"
                }
                """;
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        io.github.llm4j.loom.ast.LoomScript parsed =
                new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
        assertThat(parsed.getAgents().get(0).getOutputSchema()).isNull();
        assertThat(parsed.getAgents().get(0).getSystemPrompt()).isEqualTo("Answer in plain prose; explain your reasoning, then state your conclusions.");
        assertThat(parsed.findExtractor("AnalystFields").getModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void aTaskTextOverSeveralLinesWithAnOnFailureBlockKeepsTheExtractOutsideTheBlock() throws Exception {
        String source =
                """
                tool Files { use: file  root: "data"  mode: read }
                agent Analyst { model: "claude-sonnet-5-5"  system: "You analyse."  tools: [Files]
                    output_schema: { a: string, b: { c: string } } }
                workflow Main(q) {
                    delegate "Look at this.
                QUESTION: {q}
                MORE: {q}" to Analyst -> report retry 1 on_failure {
                        note "analysis failed: {_error}"
                    }
                    note "{report.a}"
                }
                """;
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        assertThat(text).contains("MORE: {q}\" to Analyst -> report_analysis retry 1 on_failure {");
        // the extract step comes after the closing brace of on_failure, at the statement's own indent
        assertThat(text).contains("    }\n    extract AnalystFields from report_analysis -> report");
        new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
    }

    @Test
    void anAgentDeclaredInAnImportedFileIsNotRewrittenThroughTheWrongLines() throws Exception {
        Files.writeString(
                dir.resolve("crew.loom"),
                "tool Files { use: file  root: \"data\"  mode: read }\n"
                        + "agent Analyst { model: \"m\"  system: \"You analyse.\"  tools: [Files]\n  output_schema: { a: string, b: { c: string } } }\n");
        String source =
                "import \"crew.loom\"\n\n\n\n\n\n\n\nworkflow Main(q) {\n  delegate \"Look at {q}\" to Analyst -> report\n  note \"{report.a}\"\n}\n";
        JsonNode f = finding(source, "LP10");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("another file");
    }

    static final String IN_PARALLEL =
            """
            tool Files { use: file  root: "data"  mode: read }
            agent Analyst { model: "claude-sonnet-5-5"  system: "You analyse."  tools: [Files]
                output_schema: { a: string, b: { c: string } } }
            agent Other { model: "m"  system: "You help." }
            workflow Main(q) {
                parallel {
                    delegate "Look at {q}" to Analyst -> report retry 1 on_failure {
                        note "analysis failed: {_error}"
                    }
                    delegate "Help with {q}" to Other -> help
                }
                note "{report.a} {help}"
            }
            """;

    @Test
    void aStepInsideParallelGetsItsExtractAfterTheWholeBlock() throws Exception {
        String fp = fingerprint(IN_PARALLEL);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        // each statement in a parallel block is its own branch, so the extract cannot sit beside the delegate
        assertThat(text.indexOf("extract AnalystFields")).isGreaterThan(text.indexOf("delegate \"Help with"));
        assertThat(text.indexOf("extract AnalystFields")).isLessThan(text.indexOf("note \"{report.a}"));
        new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
    }

    @Test
    void theExtractCarriesTheSameOnFailureSoAHandledFailureStillLetsTheRunContinue() throws Exception {
        String fp = fingerprint(IN_PARALLEL);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        int extract = text.indexOf("extract AnalystFields");
        assertThat(text.substring(extract)).contains("on_failure {").contains("analysis failed: {_error}");
    }

    @Test
    void anOnFailureOnTheNextLineIsFoundToo() throws Exception {
        String source =
                """
                tool Files { use: file  root: "data"  mode: read }
                agent Analyst { model: "claude-sonnet-5-5"  system: "You analyse."  tools: [Files]
                    output_schema: { a: string, b: { c: string } } }
                workflow Main(q) {
                    delegate "Look at {q}" to Analyst -> report
                        on_failure { note "failed" }
                    note "{report.a}"
                }
                """;
        String fp = fingerprint(source);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "split", "--yes")).isZero();

        String text = Files.readString(script());
        assertThat(text).contains("-> report_analysis");
        assertThat(text.substring(text.indexOf("extract AnalystFields"))).contains("on_failure { note \"failed\" }");
        new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
    }

    @Test
    void notOfferedWhenAStepChoosesItsAgentAtRunTime() throws Exception {
        String source =
                TRAPPED.replace(
                        "note \"fine:",
                        "delegate \"again\" to {pick} -> other\n        note \"fine:");
        source = source.replace("workflow Main(q) {", "workflow Main(q, pick) {");
        JsonNode f = finding(source, "LP10");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("chooses its agent at run time");
    }
}
