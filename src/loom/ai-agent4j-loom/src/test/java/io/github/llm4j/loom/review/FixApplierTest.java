package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.fix.FixApplier;
import io.github.llm4j.loom.review.fix.SourceText;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R24 to R29, R48, R67 of spec 09: fixes are verified, atomic, idempotent and change only what they
 * must.
 */
class FixApplierTest {

    @TempDir Path dir;

    /** Reviews script text with the core rules only, from a file the way the command does. */
    ReviewReport review(String text) {
        try {
            Path file = dir.resolve("main.loom");
            Files.writeString(file, text);
            LoomScript script = new LoomLoader().load(file.toString());
            ReviewInput input =
                    new ReviewInput(
                            "main.loom",
                            dir,
                            script,
                            Map.of(),
                            List.of(),
                            new GraphService().graph(file),
                            text);
            return new ReviewEngine(List.of(new DataRuleSource(List.of(Reviews.corePack()))))
                    .review(input, ReviewSettings.defaults().withLenses("all"));
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    FixApplier.Result fix(String text) {
        return FixApplier.run(text, f -> f.fix().get().safety() == FixSafety.SAFE, this::review);
    }

    static final String SEARCH =
            "agent Searcher { model: \"m\"  system: \"You search.\"  tools: [web_search] }\n"
                    + "workflow Main(topic) {\n  delegate \"Find: {topic}\" to Searcher -> found // keep this comment\n  note \"{found}\"\n}\n";

    // ---- every fix in the core pack works on its own examples (R67)

    @Test
    void everyFixOfTheCorePackFixesItsOwnFiringExampleAndGoesQuiet() throws Exception {
        List<String> noFix = new java.util.ArrayList<>();
        int rulesWithFixes = 0;
        for (RuleDoc rule : Reviews.corePack().rules()) {
            if (rule.fix() == null) continue;
            rulesWithFixes++;
            int fixed = 0;
            for (var ex : rule.fires()) {
                String text =
                        ex.file() != null
                                ? Reviews.corePack().files().read(ex.file()).orElseThrow()
                                : ex.inline();
                ReviewReport before = review(text);
                Finding f =
                        before.findings().stream()
                                .filter(x -> x.rule().equals(rule.id()))
                                .findFirst()
                                .orElse(null);
                if (f == null || f.fix().isEmpty())
                    continue; // needs settings, or a one-line script a fix will not guess at
                FixApplier.Result r =
                        FixApplier.run(text, x -> x.rule().equals(rule.id()), this::review);
                assertThat(r.changed(text)).as(rule.id() + " changes " + ex.file()).isTrue();
                assertThat(review(r.text()).findings())
                        .as(rule.id() + " is gone after its fix")
                        .noneMatch(x -> x.rule().equals(rule.id()));
                fixed++;
            }
            if (fixed == 0) noFix.add(rule.id());
        }
        assertThat(rulesWithFixes).as("rules with fixes").isGreaterThanOrEqualTo(5);
        assertThat(noFix).as("rules whose fix never applies to any of their examples").isEmpty();
    }

    @Test
    void aStatementOnALineWithOthersIsNotGuessedAt() {
        String oneLine =
                "agent A { model: \"m\"  system: \"s\"  tools: [web_search] }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n";
        Finding f =
                review(oneLine).findings().stream()
                        .filter(x -> x.rule().equals("LR01"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.fix()).isEmpty();
    }

    // ---- behaviour

    @Test
    void aSafeFixAddsTheRetryAndKeepsTheCommentAndTheRestOfTheFile() {
        FixApplier.Result r = fix(SEARCH);
        assertThat(r.applied()).extracting(FixApplier.Applied::rule).contains("LR01");
        assertThat(r.text())
                .contains("-> found retry 2 backoff 2s timeout 60s // keep this comment")
                .contains(
                        "agent Searcher { model: \"m\"  system: \"You search.\"  tools: [web_search] }")
                .contains("  note \"{found}\"");
        assertThat(r.text().lines().count()).isEqualTo(SEARCH.lines().count());
    }

    @Test
    void runningTheFixerOnItsOwnOutputChangesNothing() {
        String once = fix(SEARCH).text();
        FixApplier.Result twice = fix(once);
        assertThat(twice.text()).isEqualTo(once);
        assertThat(twice.applied()).isEmpty();
    }

    @Test
    void windowsLineEndingsAreKept() {
        String crlf = SEARCH.replace("\n", "\r\n");
        String fixed = fix(crlf).text();
        assertThat(fixed).contains("retry 2 backoff 2s timeout 60s");
        assertThat(fixed.replace("\r\n", "")).doesNotContain("\n");
        assertThat(fixed.split("\r\n", -1)).hasSize(crlf.split("\r\n", -1).length);
    }

    @Test
    void aLoopGetsItsMaximumBeforeTheBrace() {
        String loop =
                "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) {\n  loop until (x == \"yes\") {\n    delegate \"Try\" to A -> x\n  }\n}\n";
        FixApplier.Result r = fix(loop);
        assertThat(r.text()).contains("loop until (x == \"yes\") max 5 {");
        assertThat(review(r.text()).findings()).noneMatch(f -> f.rule().equals("LR03"));
    }

    @Test
    void severalFixesInOneScriptAreAppliedTogether() {
        String two =
                "agent A { model: \"m\"  system: \"s\"  tools: [web_search] }\nagent B { model: \"m\"  system: \"s\"  tools: [web_search] }\n"
                        + "workflow Main(q) {\n  delegate \"one {q}\" to A -> first\n  delegate \"two {q}\" to B -> second\n}\n";
        FixApplier.Result r = fix(two);
        assertThat(r.applied().stream().filter(a -> a.rule().equals("LR01"))).hasSize(2);
        assertThat(r.text().split("retry 2 backoff 2s timeout 60s", -1)).hasSize(3);
    }

    @Test
    void aReviewFixIsNotAppliedUnlessThatFindingIsSelected() {
        String hook =
                "tool Hook { use: webhook  url: env.HOOK_URL }\nagent N { model: \"m\"  system: \"s\"  tools: [Hook] }\n"
                        + "workflow Main(q) {\n  delegate \"tell {q}\" to N -> sent retry 2 backoff 2s timeout 60s\n}\n";
        assertThat(fix(hook).text()).isEqualTo(hook); // LR02's fix is REVIEW
        FixApplier.Result chosen = FixApplier.run(hook, f -> f.rule().equals("LR02"), this::review);
        assertThat(chosen.text()).contains("on_failure { note \"this step failed\" }");
    }

    @Test
    void addingARetryToAStepThatSendsOrWritesNeedsJudgementBecauseItCanRepeatTheEffect() {
        String hook =
                "tool Hook { use: webhook  url: env.HOOK_URL }\nagent N { model: \"m\"  system: \"s\"  tools: [Hook] }\n"
                        + "workflow Main(q) {\n  delegate \"tell {q}\" to N -> sent\n  note \"{sent}\"\n}\n";
        Finding lr01 =
                review(hook).findings().stream()
                        .filter(f -> f.rule().equals("LR01"))
                        .findFirst()
                        .orElseThrow();
        assertThat(lr01.fix().orElseThrow().safety()).isEqualTo(FixSafety.REVIEW);
        assertThat(fix(hook).text()).isEqualTo(hook);
        Finding reads =
                review(SEARCH).findings().stream()
                        .filter(f -> f.rule().equals("LR01"))
                        .findFirst()
                        .orElseThrow();
        assertThat(reads.fix().orElseThrow().safety()).isEqualTo(FixSafety.SAFE);
    }

    @Test
    void anUnusedAgentIsDeletedWholeAndTheScriptStillLoads() {
        String text =
                "agent A { model: \"m\"  system: \"s\" }\nagent Spare {\n  model: \"m\"\n  system: \"unused\"\n}\nworkflow Main(q) {\n  delegate \"x {q}\" to A -> out\n}\n";
        FixApplier.Result r = fix(text);
        assertThat(r.text()).doesNotContain("Spare");
        assertThat(r.text()).contains("agent A");
        assertThat(review(r.text()).findings()).noneMatch(f -> f.rule().equals("LM01"));
    }

    @Test
    void aFixThatWouldBreakTheScriptIsWithheldAndNothingIsChanged() {
        // the reviewer is told the fixed text does not load
        FixApplier.Result r =
                FixApplier.run(
                        SEARCH,
                        f -> true,
                        text -> {
                            if (text.contains("retry 2"))
                                throw new IllegalStateException("parse error");
                            return review(text);
                        });
        assertThat(r.text()).isEqualTo(SEARCH);
        assertThat(r.applied()).isEmpty();
        assertThat(r.withheld()).anyMatch(w -> w.contains("LR01") && w.contains("would not load"));
    }

    @Test
    void aFixThatWouldAddAHighFindingIsWithheld() {
        FixApplier.Result r =
                FixApplier.run(
                        SEARCH,
                        f -> true,
                        text -> {
                            ReviewReport real = review(text);
                            if (!text.contains("retry 2")) return real;
                            // pretend the fixed script has a new high finding
                            List<Finding> more = new java.util.ArrayList<>(real.findings());
                            more.add(
                                    ReviewEngineTest.f(
                                            "LA99",
                                            io.github.llm4j.loom.review.model.Lens.SECURITY,
                                            io.github.llm4j.loom.review.model.Severity.HIGH,
                                            1,
                                            "a new problem"));
                            return new ReviewReport(
                                    "main.loom",
                                    more,
                                    real.suppressed(),
                                    real.notes(),
                                    real.lensesRun(),
                                    real.lensesOff());
                        });
        assertThat(r.text()).isEqualTo(SEARCH);
        assertThat(r.withheld()).anyMatch(w -> w.contains("would add a high finding"));
    }

    @Test
    void sourceTextAppliesEditsLastFirstAndKeepsEverythingElse() {
        SourceText src = SourceText.of("one\ntwo\nthree\n");
        String out =
                src.apply(
                        List.of(
                                new io.github.llm4j.loom.review.model.TextEdit(
                                        "f", 1, 4, 1, 4, "!"),
                                new io.github.llm4j.loom.review.model.TextEdit(
                                        "f", 3, 1, 3, 1, ">> ")));
        assertThat(out).isEqualTo("one!\ntwo\n>> three\n");
        assertThat(SourceText.code("a // b \"c\" ")).isEqualTo("a ");
        assertThat(SourceText.code("say \"a // b\" // c")).isEqualTo("say \"a // b\" ");
        assertThat(SourceText.of("").text()).isEmpty();
        assertThat(SourceText.of("no newline").text()).isEqualTo("no newline");
    }
}
