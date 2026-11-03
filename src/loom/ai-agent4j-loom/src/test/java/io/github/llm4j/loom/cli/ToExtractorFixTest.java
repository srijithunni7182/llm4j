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
 * Phase 24, R10: LP09's {@code to-extractor} option rewrites an agent that pulls fields out of text
 * into an extractor and its delegates into extract steps. It needs judgement, so it needs --yes.
 */
class ToExtractorFixTest {

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


    static final String AGENT =
            """
            agent Reader {
                model: "m"
                system: "Extract the invoice fields from the text. Use the legal name."
                output_schema: { vendor: string, total: number, currency: enum["USD", "EUR"] }
            }
            workflow Main(text) {
                delegate "Extract the invoice from: {text}" to Reader -> invoice retry 1 on_failure { halt "no: {_error}" }
                note "{invoice.vendor}"
            }
            """;

    private String fingerprint(String source) throws Exception {
        return finding(source, "LP09").get("fingerprint").asText();
    }

    @Test
    void theFindingOffersTheToExtractorOption() throws Exception {
        JsonNode f = finding(AGENT, "LP09");

        List<String> ids = new ArrayList<>();
        f.get("options").forEach(o -> ids.add(o.get("id").asText()));
        assertThat(ids).containsExactly("to-extractor");
        assertThat(f.get("options").get(0).get("safety").asText()).isEqualTo("review");
    }

    @Test
    void aDryRunShowsTheRewriteAndWritesNothing() throws Exception {
        String fp = fingerprint(AGENT);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "to-extractor", "--dry-run")).isEqualTo(1);

        assertThat(out.toString())
                .contains("-agent Reader {")
                .contains("+extractor Reader {")
                .contains("+    instructions: \"Extract the invoice fields from the text. Use the legal name.\"")
                .contains("+    fields {")
                .contains("+        vendor: string")
                .contains("+        currency: enum[\"USD\", \"EUR\"]")
                .contains("+    delegate".replace("delegate", "extract Reader from text -> invoice retry 1"));
        assertThat(Files.readString(script())).isEqualTo(AGENT);
    }

    @Test
    void itNeedsYesBecauseItNeedsThought() throws Exception {
        String fp = fingerprint(AGENT);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "to-extractor")).isEqualTo(2);

        assertThat(err.toString()).contains("needs your judgement");
        assertThat(Files.readString(script())).isEqualTo(AGENT);
    }

    @Test
    void afterApplyingTheScriptChecksAndLp09IsGone() throws Exception {
        String fp = fingerprint(AGENT);
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "to-extractor", "--yes")).isZero();

        String rewritten = Files.readString(script());
        assertThat(rewritten)
                .contains("extractor Reader {")
                .contains("extract Reader from text -> invoice retry 1")
                .doesNotContain("agent Reader")
                .doesNotContain("delegate");
        io.github.llm4j.loom.ast.LoomScript parsed =
                new io.github.llm4j.loom.execution.LoomLoader().load(script().toString());
        assertThat(parsed.getExtractors()).extracting("name").containsExactly("Reader");
        out.reset();
        int code = run(rewritten, "--format", "json", "--min-severity", "info", "--lens", "all");
        assertThat(code).isZero();
        assertThat(out.toString()).doesNotContain("\"rule\" : \"LP09\"");
    }

    @Test
    void severalNamedInputsBecomeNamedInputsOfTheExtract() throws Exception {
        String source =
                AGENT.replace(
                        "delegate \"Extract the invoice from: {text}\"",
                        "delegate \"Email: {text}\\nOrder: {text}\"");
        JsonNode hint = finding(source, "LP09");
        String fp = hint.get("fingerprint").asText();
        out.reset();

        assertThat(rerun("--fix-finding", fp, "--option", "to-extractor", "--yes")).as(hint.get("fixHint").asText() + err.toString() + out + err.toString() + out).isZero();

        assertThat(Files.readString(script()))
                .contains("extract Reader from (email = text, order = text) -> invoice");
    }

    @Test
    void anAgentWhoseSystemPromptWritesAVariableIsNotOffered() throws Exception {
        String source = AGENT.replace("Use the legal name.", "Use the name in {text}.");
        JsonNode f = finding(source, "LP09");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("{").contains("instructions");
    }

    @Test
    void aTaskWithMeaningAroundTheVariableIsNotOffered() throws Exception {
        String source =
                AGENT.replace(
                        "delegate \"Extract the invoice from: {text}\"",
                        "delegate \"Be strict about {text} and ignore the footer\"");
        JsonNode f = finding(source, "LP09");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("carries meaning");
    }

    @Test
    void anAgentWithSettingsAnExtractorCannotHaveIsNotOffered() throws Exception {
        String source = AGENT.replace("model: \"m\"", "model: \"m\"\n    budget { tokens: 1000 }");
        JsonNode f = finding(source, "LP09");

        assertThat(f.has("options") ? f.get("options").size() : 0).isZero();
        assertThat(f.get("fixHint").asText()).contains("budget");
    }
}
