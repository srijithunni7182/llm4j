package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R5 of docs/specs/28-evaluation-first: the dataset as a page a reviewer can read and sign off. */
class RequirementsPageTest {

    @TempDir Path dir;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);

    private static final String SCRIPT =
            """
            evaluator Compliance { model: "gemini-2.5-flash" }
            policy HouseRules: "house-rules" checked by Compliance
            extractor Invoice {
                model: "gemini-2.5-flash"
                fields {
                    vendor: string
                    total: number
                }
                optional: [total]
            }
            agent Support { model: "gemini-2.5-flash"  system: "s" }
            agent Silent { model: "gemini-2.5-flash"  system: "s" }
            workflow Main(q) {
                delegate "{q}" to Support -> reply
                check reply against HouseRules using Compliance -> verdict on_violation { fail }
                extract Invoice from reply -> invoice
                return reply
            }
            """;

    private Path script;

    @BeforeEach
    void project() throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(
                dir.resolve("policies/house-rules.md"),
                "## no_refund_promise\nNever promise a refund.\n");
        script = Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Path g = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                g.resolve("dataset.yaml"), "version: 3\ndimensions:\n  correctness: right\n");
        Files.writeString(
                g.resolve("support.yaml"),
                """
                - id: s-1
                  name: duplicate charge
                  input: "I was charged twice, 240 dollars"
                  expected_output_contains: approval
                  expected_output_not_contains: ["4111"]
                  expected_min_words: 50
                  rubric: ["asks a person before refunding"]
                  dimensions: [correctness]
                """);
        Files.writeString(
                g.resolve("invoice.yaml"),
                "- id: i-1\n  input: \"Acme, total 240\"\n  expected_fields: { vendor: Acme, total: 240 }\n"
                        + "- id: i-2\n  input: \"Acme, thanks\"\n  expected_fields: { vendor: Acme }\n  expected_missing: [total]\n");
        Files.writeString(
                g.resolve("compliance.yaml"),
                """
                - id: c-1
                  policy: HouseRules
                  text: "We will refund you."
                  expected: { no_refund_promise: violated }
                - id: c-2
                  policy: HouseRules
                  near_miss: true
                  text: "I cannot promise a refund."
                  expected: { no_refund_promise: clean }
                """);
    }

    private String page(String format) throws Exception {
        return RequirementsPage.render(
                new LoomLoader().load(script.toString()),
                script,
                format,
                CLOCK,
                new io.github.llm4j.loom.loop.Masker()::mask);
    }

    @Test
    void anAgentsCasesAreWrittenAsPlainSentences() throws Exception {
        String md = page("md");
        assertThat(md)
                .contains("Support")
                .contains("When someone gives *I was charged twice, 240 dollars*")
                .contains("must mention *approval*")
                .contains("must never contain *4111*")
                .contains("at least 50 words")
                .contains("a judge must confirm that it *asks a person before refunding*");
    }

    @Test
    void anExtractorShowsItsInputsAndTheFieldsExpectedAndTheMissingOnes() throws Exception {
        assertThat(page("md"))
                .contains("Invoice")
                .contains("Acme, total 240")
                .contains("vendor = Acme")
                .contains("total = 240")
                .contains("must be missing: total");
    }

    @Test
    void aPolicyRuleShowsItsTextThenWhatBreaksItAndWhatMustNotCount() throws Exception {
        String md = page("md");
        assertThat(md).contains("HouseRules").contains("no_refund_promise").contains("Never promise a refund.");
        assertThat(md.indexOf("We will refund you.")).isPositive();
        assertThat(md).contains("Breaks the rule").contains("Must not count").contains("near miss").contains("I cannot promise a refund.");
    }

    @Test
    void theFalseAlarmLimitIsShownBesideThePolicyRules() throws Exception {
        assertThat(page("md")).contains("False-alarm limit: Compliance may flag at most 10.0%");
    }

    @Test
    void itShowsTheDatasetIdentityAndThePoliciesVersionAndHash() throws Exception {
        DatasetIdentity id = DatasetIdentity.of(dir.resolve("eval/golden"));
        assertThat(page("md")).contains("version 3").contains(id.hash()).contains("HouseRules");
    }

    @Test
    void itListsWhatHasNoRequirementYet() throws Exception {
        assertThat(page("md")).contains("No requirement yet").contains("Silent");
    }

    @Test
    void anAgentWithCasesIsNotListedAsMissing() throws Exception {
        String md = page("md");
        String tail = md.substring(md.indexOf("No requirement yet"));
        assertThat(tail).doesNotContain("Support").doesNotContain("Invoice");
    }

    @Test
    void personalDataInACaseIsMasked() throws Exception {
        Files.writeString(
                dir.resolve("eval/golden/silent.yaml"),
                "- id: x-1\n  input: \"Write to jane.doe@example.com about card 4111 1111 1111 1111\"\n  rubric: [\"polite\"]\n");
        String md = page("md");
        assertThat(md).doesNotContain("jane.doe@example.com").doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void theHtmlFormIsAPageWithTheTextEscaped() throws Exception {
        Files.writeString(
                dir.resolve("eval/golden/silent.yaml"),
                "- id: x-1\n  input: \"a <script>alert(1)</script> b\"\n  rubric: [\"polite\"]\n");
        String html = page("html");
        assertThat(html).startsWith("<!doctype html>").contains("<h1>").contains("&lt;script&gt;").doesNotContain("<script>alert");
    }

    @Test
    void needsNoModelNoKeyAndNoNetworkItJustReadsFiles() throws Exception {
        assertThat(page("md")).isNotBlank();
    }

    @Test
    void anUnknownFormatIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> page("pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("md")
                .hasMessageContaining("html");
    }

    // ── approvals ────────────────────────────────────────────────────────────────────────────

    @Test
    void beforeAnyApprovalThePageSaysSo() throws Exception {
        assertThat(page("md")).contains("Not approved yet");
    }

    @Test
    void approvingRecordsTheExactIdentityAndThePageShowsWhoAndWhen() throws Exception {
        RequirementsApprovals.approve(new LoomLoader().load(script.toString()), script, "Dana", "Read it with legal", CLOCK);
        assertThat(Files.readString(dir.resolve("eval/requirements-approvals.yaml")))
                .contains("Dana")
                .contains("Read it with legal")
                .contains("2026-11-04")
                .contains(DatasetIdentity.of(dir.resolve("eval/golden")).hash());
        assertThat(page("md")).contains("Approved by Dana on 2026-11-04");
    }

    @Test
    void changingACaseAfterApprovalSaysChangedSinceApproved() throws Exception {
        RequirementsApprovals.approve(new LoomLoader().load(script.toString()), script, "Dana", "ok", CLOCK);
        Files.writeString(dir.resolve("eval/golden/support.yaml"), Files.readString(dir.resolve("eval/golden/support.yaml")).replace("240", "250"));
        assertThat(page("md")).contains("changed since approved by Dana on 2026-11-04");
    }

    @Test
    void changingAPolicyAfterApprovalAlsoCountsAsAChange() throws Exception {
        RequirementsApprovals.approve(new LoomLoader().load(script.toString()), script, "Dana", "ok", CLOCK);
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_refund_promise\nNever promise money back.\n");
        assertThat(page("md")).contains("changed since approved by Dana");
    }

    @Test
    void anApprovalNeedsAnApproverAndAReason() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RequirementsApprovals.approve(new LoomLoader().load(script.toString()), script, " ", "why", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--approver");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RequirementsApprovals.approve(new LoomLoader().load(script.toString()), script, "Dana", "", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--why");
    }
}
