package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The report has one structure, always: the same nine sections in the same order in the terminal,
 * the page and the JSON, a section with nothing to show still says why, and the By-agent view is
 * there for every run, without a tag or a flag.
 */
class ReportContractTest {
    private static final List<String> TITLES =
            List.of(
                    "Read this first",
                    "Overall",
                    "By agent",
                    "By dimension",
                    "By slice",
                    "Cases",
                    "Cost",
                    "Cautions",
                    "How to reproduce");

    private static final List<String> IDS =
            List.of(
                    "read-first",
                    "overall",
                    "by-agent",
                    "by-dimension",
                    "by-slice",
                    "cases",
                    "cost",
                    "cautions",
                    "reproduce");

    private static AgentFacts agent(String name, long calls, String cost, long cut, long fell) {
        return new AgentFacts(name, calls, 100 * calls, 200 * calls, cost == null ? null : new BigDecimal(cost), cut, fell);
    }

    private static ScenarioResult result(
            String target,
            String kind,
            String id,
            Status status,
            String output,
            String detail,
            List<String> tags,
            List<String> dimensions,
            AgentFacts... agents) {
        List<ScenarioResult.Check> checks =
                List.of(new ScenarioResult.Check("rubric", "Is whole", status, detail));
        return new ScenarioResult(
                target, kind, "f.yaml", id, id, status, checks, new BigDecimal("0.01"), null,
                "the question", output, tags, dimensions, List.of(agents));
    }

    private static EvalReport.Run run() {
        List<ScenarioResult> results = new ArrayList<>();
        results.add(
                result("Writer", "agent", "w-1", Status.PASS, "a good article", "score 0.90: whole",
                        List.of("agent:Writer", "split:validation"), List.of("completeness"),
                        agent("Writer", 2, "0.002", 0, 0)));
        results.add(
                result("Editor", "agent", "e-1", Status.FAIL, "bad padding text", "score 0.40: padding",
                        List.of("agent:Editor"), List.of("style"), agent("Editor", 1, "0.001", 1, 0)));
        results.add(
                result("Main", "workflow", "m-1", Status.UNJUDGED, "the final text", "no judge ran",
                        List.of(), List.of(), agent("Writer", 1, "0.001", 0, 0), agent("Checker", 1, "0.001", 0, 1)));
        return new EvalReport.Run(
                "main.loom", false, results, 0, null, Map.of(),
                new EvalReport.JudgeFacts("claude-haiku", 3, 900, 90, new BigDecimal("0.004"), false),
                Map.of("dataset", "eval/golden", "weave", "6.2.0-SNAPSHOT"),
                "weave eval main.loom --max-tokens 200000");
    }

    private static EvalReport.Run emptyRun() {
        return new EvalReport.Run("main.loom", true, List.of(), 0, null, Map.of());
    }


    @Test
    void theTerminalHasTheNineSectionsInOrder() {
        String text = EvalSections.terminal(run());

        List<Integer> at = new ArrayList<>();
        for (String title : TITLES) {
            int i = text.indexOf("== " + title + " ==");
            assertThat(i).as(title).isNotNegative();
            at.add(i);
        }
        assertThat(at).isSorted();
    }

    @Test
    void thePageHasTheSameSectionsInTheSameOrder() {
        String html = EvalReport.html(run());

        List<Integer> at = new ArrayList<>();
        for (String id : IDS) {
            int i = html.indexOf("<section id=\"" + id + "\"");
            assertThat(i).as(id).isNotNegative();
            at.add(i);
        }
        assertThat(at).isSorted();
    }

    @Test
    void theJsonHasTheSameSectionsInTheSameOrder() throws Exception {
        JsonNode json = new ObjectMapper().readTree(EvalReport.json(run()));

        List<String> keys = new ArrayList<>();
        json.get("sections").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyElementsOf(IDS);
    }

    @Test
    void aRunWithNothingInItStillHasEverySectionAndEachSaysWhy() throws Exception {
        String text = EvalSections.terminal(emptyRun());

        for (String title : TITLES) assertThat(text).contains("== " + title + " ==");
        assertThat(text)
                .contains("no agent ran")
                .contains("no case ran")
                .contains("no dimension");
        JsonNode json = new ObjectMapper().readTree(EvalReport.json(emptyRun()));
        assertThat(json.get("sections").size()).isEqualTo(IDS.size());
    }

    @Test
    void theByAgentViewHasARowPerAgentWithoutAnyTagOrFlag() {
        // the workflow-only agent has no agent: tag anywhere
        String text = EvalSections.terminal(run());
        String byAgent = section(text, "By agent", "By dimension");

        assertThat(byAgent).contains("Writer").contains("Editor").contains("Checker");
    }

    @Test
    void eachAgentRowShowsItsPassRateItsCostItsCutAnswersAndItsFallbacks() {
        String byAgent = section(EvalSections.terminal(run()), "By agent", "By dimension");

        String writer = row(byAgent, "Writer");
        assertThat(writer).contains("1 of 1").contains("interval").contains("$0.0030").contains("0 cut");
        String editor = row(byAgent, "Editor");
        assertThat(editor).contains("0 of 1").contains("1 cut");
        String checker = row(byAgent, "Checker");
        assertThat(checker).contains("1 fallback");
    }

    @Test
    void anAgentSeenOnlyInsideAWorkflowSaysItsQualityIsNotMeasuredOnItsOwn() {
        String checker = row(section(EvalSections.terminal(run()), "By agent", "By dimension"), "Checker");

        assertThat(checker).contains("only inside workflow runs");
    }

    @Test
    void theWorstOutputsOfAnAgentAreShownWithTheReason() {
        String byAgent = section(EvalSections.terminal(run()), "By agent", "By dimension");

        assertThat(byAgent).contains("bad padding text").contains("padding");
    }

    @Test
    void theCostSectionSeparatesAgentsJudgeAndTotalAndGivesCostPerSuccess() {
        String cost = section(EvalSections.terminal(run()), "Cost", "Cautions");

        assertThat(cost).contains("agents").contains("judge").contains("total").contains("per passing case");
        assertThat(cost).contains("$0.0040");
    }

    @Test
    void theCautionsListEveryWarningAndHideNone() {
        String cautions = section(EvalSections.terminal(run()), "Cautions", "How to reproduce");

        assertThat(cautions)
                .contains("unjudged")
                .contains("no held-out")
                .contains("judge has not been validated")
                .contains("fewer than 10")
                .contains("cut at the output limit")
                .contains("took their fallback");
    }

    @Test
    void theCasesSectionShowsEveryOutputWithEachChecksReason() {
        String cases = section(EvalSections.terminal(run()), "Cases", "Cost");

        assertThat(cases).contains("a good article").contains("bad padding text").contains("the final text");
        assertThat(cases).contains("score 0.40: padding");
    }

    @Test
    void howToReproduceNamesTheCommandAndTheSettings() {
        String reproduce = EvalSections.terminal(run()).substring(EvalSections.terminal(run()).indexOf("== How to reproduce =="));

        assertThat(reproduce).contains("weave eval main.loom --max-tokens 200000").contains("eval/golden");
    }

    private static String section(String text, String from, String to) {
        int a = text.indexOf("== " + from + " ==");
        int b = text.indexOf("== " + to + " ==");
        assertThat(a).isNotNegative();
        assertThat(b).isGreaterThan(a);
        return text.substring(a, b);
    }

    private static String row(String section, String agent) {
        return section.lines().filter(l -> l.stripLeading().startsWith(agent)).findFirst().orElse("");
    }
}
