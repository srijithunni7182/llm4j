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
 * The developer decides: a finding offers options, the developer picks one and answers its
 * questions, sees the change, and only then is it written, after the same checks a fix gets.
 */
class AssistedFixTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Deque<String> typed = new ArrayDeque<>();
    List<String> asked = new ArrayList<>();

    static final String SEARCH =
            "agent A { model: \"m\"  system: \"s\"  tools: [web_search] }\n"
                    + "workflow Main(topic) {\n  delegate \"one {topic}\" to A -> first\n  note \"{first}\"\n}\n";

    static final String MAIL =
            "tool Mail { use: webhook  url: env.HOOK }\n"
                    + "agent N { model: \"m\"  system: \"s\"  tools: [Mail] }\n"
                    + "workflow Main(q) {\n  delegate \"tell {q}\" to N -> sent\n  note \"{sent}\"\n}\n";

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

    @Test
    void aFindingListsItsOptionsWithTheirQuestionsInJson() throws Exception {
        JsonNode f = finding(SEARCH, "LR01");
        List<String> ids = new ArrayList<>();
        f.get("options").forEach(o -> ids.add(o.get("id").asText()));
        assertThat(ids).containsExactly("retry-timeout", "timeout-only");
        JsonNode ask = f.get("options").get(1).get("ask").get(0);
        assertThat(ask.get("name").asText()).isEqualTo("seconds");
        assertThat(ask.get("default").asText()).isEqualTo("60");
    }

    @Test
    void theTextReportNamesTheOptions() throws Exception {
        assertThat(run(SEARCH)).isZero();
        assertThat(out.toString()).contains("options: [retry-timeout]").contains("[timeout-only]");
    }

    @Test
    void anOptionIsAppliedWithTheDevelopersAnswer() throws Exception {
        String fp = finding(SEARCH, "LR01").get("fingerprint").asText();
        out.reset();
        assertThat(rerun("--fix-finding", fp, "--option", "timeout-only", "--set", "seconds=30"))
                .isZero();
        assertThat(Files.readString(script()))
                .contains("-> first timeout 30s")
                .doesNotContain("retry");
        assertThat(out.toString()).contains("Applied option 'timeout-only'");
    }

    @Test
    void anOptionThatNeedsJudgementNeedsYesButADryRunShowsTheChange() throws Exception {
        String fp = finding(MAIL, "LR01").get("fingerprint").asText();
        out.reset();
        assertThat(rerun("--fix-finding", fp, "--option", "retry-timeout", "--dry-run"))
                .isEqualTo(1);
        assertThat(out.toString()).contains("+").contains("retry 2 backoff 2s timeout 60s");
        assertThat(Files.readString(script())).isEqualTo(MAIL);
        assertThat(rerun("--fix-finding", fp, "--option", "retry-timeout")).isEqualTo(2);
        assertThat(err.toString()).contains("needs your judgement");
        assertThat(Files.readString(script())).isEqualTo(MAIL);
        assertThat(
                        rerun(
                                "--fix-finding",
                                fp,
                                "--option",
                                "retry-timeout",
                                "--set",
                                "policy=retry 1 timeout 20s",
                                "--yes"))
                .isZero();
        assertThat(Files.readString(script())).contains("-> sent retry 1 timeout 20s");
    }

    @Test
    void anUnknownOptionOrABadFingerprintIsRefusedAndNothingChanges() throws Exception {
        String fp = finding(SEARCH, "LR01").get("fingerprint").asText();
        assertThat(rerun("--fix-finding", fp, "--option", "nope")).isEqualTo(2);
        assertThat(err.toString()).contains("has no option 'nope'").contains("timeout-only");
        err.reset();
        assertThat(rerun("--fix-finding", "0000000000000000", "--option", "timeout-only"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no open finding");
        assertThat(rerun("--option", "timeout-only")).isEqualTo(2);
        assertThat(Files.readString(script())).isEqualTo(SEARCH);
    }

    @Test
    void anAnswerThatBreaksTheScriptIsRefusedWithTheReason() throws Exception {
        String fp = finding(SEARCH, "LR01").get("fingerprint").asText();
        err.reset();
        assertThat(
                        rerun(
                                "--fix-finding",
                                fp,
                                "--option",
                                "timeout-only",
                                "--set",
                                "seconds=} oops {"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("would not load");
        assertThat(Files.readString(script())).isEqualTo(SEARCH);
    }

    @Test
    void setNeedsNameEqualsValue() throws Exception {
        String fp = finding(SEARCH, "LR01").get("fingerprint").asText();
        assertThat(rerun("--fix-finding", fp, "--option", "timeout-only", "--set", "seconds"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("name=value");
    }

    @Test
    void assistAsksChoosesAsksAgainShowsTheChangeAndWritesOnConfirm() throws Exception {
        typed.addAll(List.of("2", "45", "y"));
        out.reset();
        assertThat(run(SEARCH, "--assist", "--fix-rule", "LR01")).isZero();
        assertThat(Files.readString(script())).contains("-> first timeout 45s");
        assertThat(out.toString())
                .contains("What would you like to do?")
                .contains("1) Retry and time out")
                .contains("2) Time out only")
                .contains("+")
                .contains("1 change(s) made");
        assertThat(asked).anyMatch(q -> q.contains("Time out after how many seconds [60]"));
    }

    @Test
    void assistLeavesTheScriptAloneWhenSkippedOrNotConfirmed() throws Exception {
        typed.addAll(List.of("s"));
        assertThat(run(SEARCH, "--assist", "--fix-rule", "LR01")).isZero();
        assertThat(Files.readString(script())).isEqualTo(SEARCH);
        assertThat(out.toString()).contains("Skipped").contains("Nothing changed");
        typed.clear();
        typed.addAll(List.of("2", "", "n"));
        assertThat(rerun("--assist", "--fix-rule", "LR01")).isZero();
        assertThat(Files.readString(script())).isEqualTo(SEARCH);
    }

    @Test
    void anEmptyAnswerTakesTheDefault() throws Exception {
        typed.addAll(List.of("2", "", "yes"));
        assertThat(run(SEARCH, "--assist", "--fix-rule", "LR01")).isZero();
        assertThat(Files.readString(script())).contains("timeout 60s");
    }

    @Test
    void failureOptionsOfferANoteOrAPersonAndKeepTheDevelopersWords() throws Exception {
        typed.addAll(List.of("2", "Check the mail log", "mailed", "y"));
        assertThat(run(MAIL, "--assist", "--fix-rule", "LR02")).isZero();
        assertThat(Files.readString(script()))
                .contains("on_failure { human_prompt \"Check the mail log\" -> mailed }");
    }

    @Test
    void deletingAnUnusedAgentIsAnOptionNotAnAutomaticFix() throws Exception {
        String src = SEARCH + "agent Spare { model: \"m\"  system: \"s\" }\n";
        String fp = finding(src, "LM01").get("fingerprint").asText();
        assertThat(rerun("--lens", "all", "--fix-finding", fp, "--option", "delete")).isZero();
        assertThat(Files.readString(script())).doesNotContain("Spare");
    }

    @Test
    void maskOrBlockIsTheDevelopersCallForPersonalData() throws Exception {
        String src =
                "tool Mail { use: webhook  url: env.HOOK }\n"
                        + "agent N { model: \"m\"  system: \"s\"  tools: [Mail]  guard { pii: warn } }\n"
                        + "workflow Main(q) { delegate \"tell {q}\" to N -> sent  note \"{sent}\" }\n";
        JsonNode f = finding(src, "LY02");
        assertThat(f.get("options")).hasSize(2);
        assertThat(
                        rerun(
                                "--fix-finding",
                                f.get("fingerprint").asText(),
                                "--option",
                                "block",
                                "--yes"))
                .isZero();
        assertThat(Files.readString(script())).contains("pii: block");
    }
}
