package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R4 of docs/specs/28-evaluation-first: an extractor or evaluator cannot be used untested. */
class NodeCasesGateTest {

    @TempDir Path dir;

    private static final String EXTRACTOR_SCRIPT =
            """
            extractor Invoice {
                model: "gemini-2.5-flash"
                fields {
                    total: number
                    vendor: string
                }
                optional: [total]
            }
            workflow Main(text) {
                extract Invoice from text -> invoice
                return invoice
            }
            """;

    private static final String EVALUATOR_SCRIPT =
            """
            evaluator Compliance { model: "gemini-2.5-flash" }
            policy HouseRules: "house-rules" checked by Compliance
            agent Support { model: "gemini-2.5-flash"  system: "s" }
            workflow Main(q) {
                delegate "{q}" to Support -> reply
                check reply against HouseRules using Compliance -> verdict on_violation { fail }
                return reply
            }
            """;

    private Path script(String source) throws Exception {
        Path policies = Files.createDirectories(dir.resolve("policies"));
        Files.writeString(
                policies.resolve("house-rules.md"),
                "## no_refund_promise\nNever promise a refund.\n\n## no_threats\nNever threaten.\n");
        return Files.writeString(dir.resolve("main.loom"), source);
    }

    private void golden(String file, String yaml) throws Exception {
        Path g = Files.createDirectories(dir.resolve("eval").resolve("golden"));
        Files.writeString(g.resolve(file), yaml);
    }

    private List<String> problems(String source) throws Exception {
        Path s = script(source);
        return NodeCasesGate.problems(new LoomLoader().load(s.toString()), s).stream()
                .map(p -> p.message())
                .toList();
    }

    private static final String BOTH_RULES_BOTH_WAYS =
            """
            - id: c1
              policy: HouseRules
              text: "We will refund you."
              expected: { no_refund_promise: violated, no_threats: clean }
            - id: c2
              policy: HouseRules
              text: "Pay or else."
              expected: { no_refund_promise: clean, no_threats: violated }
            """;

    @Test
    void anExtractorWithNoCasesIsRejectedAndTheMessageNamesTheFile() throws Exception {
        assertThat(problems(EXTRACTOR_SCRIPT))
                .singleElement()
                .asString()
                .contains("Extractor `Invoice` has no golden cases")
                .contains("eval/golden/invoice.yaml")
                .contains("weave eval");
    }

    @Test
    void aScaffoldWithNoCasesNeverSatisfiesTheGate() throws Exception {
        golden("invoice.yaml", "# Cases for the extractor Invoice.\n");
        assertThat(problems(EXTRACTOR_SCRIPT)).singleElement().asString().contains("no golden cases");
    }

    @Test
    void oneCaseThatNamesFieldsIsEnoughForAnExtractor() throws Exception {
        golden(
                "Invoice.yaml",
                """
                - id: i1
                  input: "Invoice from Acme, total 240."
                  expected_fields: { total: 240, vendor: Acme }
                """);
        assertThat(problems(EXTRACTOR_SCRIPT)).isEmpty();
    }

    @Test
    void expectedMissingAloneCountsAsAnExpectation() throws Exception {
        golden(
                "invoice.yml",
                """
                - id: i1
                  input: "Invoice from Acme."
                  expected_fields: { vendor: Acme }
                  expected_missing: [total]
                """);
        assertThat(problems(EXTRACTOR_SCRIPT)).isEmpty();
    }

    @Test
    void anExtractorCaseWithNoExpectationIsAStructureProblem() throws Exception {
        golden("invoice.yaml", "- id: i1\n  input: \"text\"\n");
        assertThat(problems(EXTRACTOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("i1").contains("expected_fields"));
    }

    @Test
    void aFieldTheExtractorDoesNotHaveIsAStructureProblem() throws Exception {
        golden("invoice.yaml", "- id: i1\n  input: \"t\"\n  expected_fields: { totl: 1 }\n");
        assertThat(problems(EXTRACTOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("totl").contains("total"));
    }

    @Test
    void aRequiredFieldCannotBeExpectedMissing() throws Exception {
        golden("invoice.yaml", "- id: i1\n  input: \"t\"\n  expected_missing: [vendor]\n");
        assertThat(problems(EXTRACTOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("vendor").contains("required"));
    }

    @Test
    void aDuplicateIdIsAStructureProblem() throws Exception {
        golden(
                "invoice.yaml",
                "- id: i1\n  input: \"a\"\n  expected_fields: { vendor: A }\n- id: i1\n  input: \"b\"\n  expected_fields: { vendor: B }\n");
        assertThat(problems(EXTRACTOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("i1").contains("twice"));
    }

    @Test
    void aFileThatIsNotValidYamlIsReportedByNameNotAsNoCases() throws Exception {
        golden("invoice.yaml", "- id: [unclosed\n");
        assertThat(problems(EXTRACTOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("invoice.yaml").doesNotContain("has no golden cases"));
    }

    @Test
    void anEvaluatorWithNoFileIsRejectedForEveryRuleOfItsPolicy() throws Exception {
        List<String> p = problems(EVALUATOR_SCRIPT);
        assertThat(p)
                .anySatisfy(
                        m ->
                                assertThat(m)
                                        .contains("Evaluator `Compliance`")
                                        .contains("HouseRules.no_refund_promise")
                                        .contains("no clean case")
                                        .contains("eval/golden/compliance.yaml"));
        assertThat(p).anySatisfy(m -> assertThat(m).contains("HouseRules.no_threats"));
    }

    @Test
    void aRuleWithABreakingCaseButNoCleanCaseIsRejected() throws Exception {
        golden(
                "compliance.yaml",
                """
                - id: c1
                  policy: HouseRules
                  text: "We will refund you."
                  expected: { no_refund_promise: violated, no_threats: violated }
                """);
        assertThat(problems(EVALUATOR_SCRIPT))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m).contains("has no clean case").doesNotContain("has no case that breaks"));
    }

    @Test
    void aRuleWithACleanCaseButNoBreakingCaseIsRejected() throws Exception {
        golden(
                "compliance.yaml",
                """
                - id: c1
                  policy: HouseRules
                  text: "Happy to help."
                  expected: { no_refund_promise: clean, no_threats: clean }
                """);
        assertThat(problems(EVALUATOR_SCRIPT))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m).contains("has no case that breaks").doesNotContain("no clean case"));
    }

    @Test
    void aBreakingAndACleanCaseForEveryRuleSatisfiesTheGate() throws Exception {
        golden("compliance.yaml", BOTH_RULES_BOTH_WAYS);
        assertThat(problems(EVALUATOR_SCRIPT)).isEmpty();
    }

    @Test
    void aNearMissCountsAsClean() throws Exception {
        golden(
                "compliance.yaml",
                """
                - id: c1
                  policy: HouseRules
                  text: "Refunds are not something I can promise, but I can ask a colleague."
                  near_miss: true
                  expected: { no_refund_promise: clean, no_threats: clean }
                - id: c2
                  policy: HouseRules
                  text: "We will refund you. Pay or else."
                  expected: { no_refund_promise: violated, no_threats: violated }
                """);
        assertThat(problems(EVALUATOR_SCRIPT)).isEmpty();
    }

    @Test
    void aRuleThatTheCaseNamesButThePolicyDoesNotHaveIsAStructureProblem() throws Exception {
        golden(
                "compliance.yaml",
                BOTH_RULES_BOTH_WAYS
                        + "- id: c3\n  policy: HouseRules\n  text: \"x\"\n  expected: { no_refunds_promise: violated }\n");
        assertThat(problems(EVALUATOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("no_refunds_promise").contains("no_refund_promise"));
    }

    @Test
    void aMalformedExpectationIsAStructureProblem() throws Exception {
        golden(
                "compliance.yaml",
                BOTH_RULES_BOTH_WAYS
                        + "- id: c3\n  policy: HouseRules\n  text: \"x\"\n  expected: { no_threats: maybe }\n");
        assertThat(problems(EVALUATOR_SCRIPT))
                .anySatisfy(m -> assertThat(m).contains("c3").contains("violated").contains("clean"));
    }

    @Test
    void aDeclaredButNeverUsedNodeIsNotRequired() throws Exception {
        assertThat(
                        problems(
                                """
                                extractor Spare {
                                    model: "gemini-2.5-flash"
                                    fields { a: string }
                                }
                                workflow Main(q) { note "hi" }
                                """))
                .isEmpty();
    }

    @Test
    void theGateReadsTheFilesTheSameWayWeaveEvalFindsThem() throws Exception {
        golden("INVOICE.yaml", "- id: i1\n  input: \"t\"\n  expected_fields: { vendor: A }\n");
        assertThat(problems(EXTRACTOR_SCRIPT)).isEmpty();
    }
}
