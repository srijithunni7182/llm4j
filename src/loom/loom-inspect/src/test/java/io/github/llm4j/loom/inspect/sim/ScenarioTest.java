package io.github.llm4j.loom.inspect.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.inspect.state.WindowBook;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R22: each scenario runs with no provider and produces the view its row of the design says. */
class ScenarioTest {

    @TempDir Path dir;

    private final RunRegistry registry = new RunRegistry();

    private Map<String, Object> run(String name) {
        Scenarios.Scenario s = Scenarios.get(name);
        Scenarios.Outcome outcome = Scenarios.runInto(registry, "sim", s, "run-" + name, dir);
        assertThat(outcome.message()).isNotNull();
        return view(name);
    }

    private Map<String, Object> view(String name) {
        return registry.find("sim", Ids.traceId("run-" + name)).orElseThrow().view();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> v, String key) {
        return (List<Map<String, Object>>) v.get(key);
    }

    private static List<Map<String, Object>> incidents(Map<String, Object> v, String kind) {
        return list(v, "incidents").stream().filter(i -> kind.equals(i.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(Map<String, Object> v, String agent) {
        return (Map<String, Object>)
                list(v, "agents").stream()
                        .filter(a -> agent.equals(a.get("name")))
                        .findFirst()
                        .orElseThrow()
                        .get("context");
    }

    private static Map<String, Object> row(Map<String, Object> v, String key, String name) {
        return list(v, key).stream()
                .filter(r -> name.equals(r.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theNamesAreThereAndEachScenarioDescribesItself() {
        assertThat(Scenarios.names())
                .containsExactly(
                        "context-fills",
                        "compaction-thrash",
                        "no-window",
                        "flaky-tool",
                        "task-diverted",
                        "task-broken",
                        "delegate-broken",
                        "latency-spike",
                        "truncated-answers",
                        "trifecta",
                        "trifecta-accepted",
                        "injected-page",
                        "access-refused");
        for (String n : Scenarios.names())
            assertThat(Scenarios.get(n).description()).as(n).isNotBlank();
    }

    @Test
    void anUnknownNameIsRefusedWithTheListOfNames() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Scenarios.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("context-fills")
                .hasMessageContaining("injected-page");
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextFillsClimbsPastTheWarningWithCompactionsAndAContextIncident() {
        Map<String, Object> v = run("context-fills");
        Map<String, Object> c = context(v, "Researcher");
        assertThat(
                        ((List<Map<String, Object>>) c.get("events"))
                                .stream().filter(e -> "COMPACTED".equals(e.get("outcome"))).count())
                .isGreaterThanOrEqualTo(3);
        assertThat(
                        ((List<Map<String, Object>>) c.get("points"))
                                .stream()
                                        .mapToDouble(
                                                p ->
                                                        ((Number) p.getOrDefault("fraction", 0.0))
                                                                .doubleValue())
                                        .max()
                                        .orElse(0))
                .isGreaterThanOrEqualTo(0.85);
        assertThat(c).containsEntry("windowSource", "process");
        assertThat(incidents(v, "CONTEXT")).isNotEmpty();
    }

    @Test
    void compactionThrashRaisesTheThrashIncident() {
        Map<String, Object> v = run("compaction-thrash");
        assertThat(incidents(v, "THRASH")).isNotEmpty();
    }

    @Test
    void noWindowShowsTokensWithoutAFractionUntilTheCatalogHasOne() {
        Map<String, Object> v = run("no-window");
        Map<String, Object> c = context(v, "Reader");
        assertThat(c).doesNotContainKeys("usable", "fraction", "windowSource");
        assertThat((List<?>) c.get("points")).isNotEmpty();
        registry.setWindows(WindowBook.parse("sim-nowindow = 3000").book());
        Map<String, Object> after = context(view("no-window"), "Reader");
        assertThat(after)
                .containsEntry("usable", 3000L)
                .containsEntry("windowSource", "catalog")
                .containsKey("fraction");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFlakyToolIsFailingAndASteadyOneIsHealthy() {
        Map<String, Object> v = run("flaky-tool");
        assertThat(((Map<String, Object>) row(v, "tools", "Flaky").get("health")).get("state"))
                .isEqualTo("failing");
        assertThat(((Map<String, Object>) row(v, "tools", "Steady").get("health")).get("state"))
                .isEqualTo("healthy");
        assertThat(incidents(v, "TOOL_RATE")).isNotEmpty();
    }

    @Test
    void aDivertedTaskFinishesTheRunAndRaisesDiverted() {
        Map<String, Object> v = run("task-diverted");
        assertThat(v).containsEntry("status", "finished");
        assertThat(incidents(v, "DIVERTED"))
                .singleElement()
                .satisfies(i -> assertThat(i.get("title").toString()).contains("IssueRefund"));
        assertThat(incidents(v, "BROKEN")).isEmpty();
        assertThat(row(v, "tasks", "IssueRefund")).containsEntry("errors", 1L);
    }

    @Test
    void aBrokenTaskFailsTheRunAndNamesItInAnIncidentThatStaysOpen() {
        Map<String, Object> v = run("task-broken");
        assertThat(v).containsEntry("status", "failed");
        assertThat(incidents(v, "BROKEN"))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i)
                                    .containsEntry("state", "open")
                                    .containsEntry("severity", "high");
                            assertThat(i.get("title").toString())
                                    .contains("task")
                                    .contains("IssueRefund");
                        });
        assertThat(row(v, "tasks", "IssueRefund")).containsEntry("errors", 1L);
    }

    @Test
    void aBrokenDelegateIsNamedTheSameWay() {
        Map<String, Object> v = run("delegate-broken");
        assertThat(v).containsEntry("status", "failed");
        assertThat(incidents(v, "BROKEN"))
                .singleElement()
                .satisfies(
                        i ->
                                assertThat(i.get("title").toString())
                                        .contains("delegate")
                                        .contains("Writer"));
    }

    @Test
    void aSuddenlySlowToolRaisesALatencySpike() {
        Map<String, Object> v = run("latency-spike");
        assertThat(incidents(v, "SPIKE")).isNotEmpty();
    }

    @Test
    void answersCutOffAtTheTokenLimitRaiseTruncated() {
        Map<String, Object> v = run("truncated-answers");
        assertThat(incidents(v, "TRUNCATED")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTrifectaIsHighAndWhenAcceptedInfoWithItsReason() {
        Map<String, Object> v = run("trifecta");
        assertThat(incidents(v, "TRIFECTA"))
                .singleElement()
                .satisfies(i -> assertThat(i).containsEntry("severity", "high"));
        Map<String, Object> accepted = run("trifecta-accepted");
        assertThat(incidents(accepted, "TRIFECTA"))
                .singleElement()
                .satisfies(i -> assertThat(i).containsEntry("severity", "info"));
        Map<String, Object> security = (Map<String, Object>) accepted.get("security");
        assertThat(((List<Map<String, Object>>) security.get("agents")).get(0))
                .containsEntry("verdict", "accepted")
                .containsKey("acceptedReason");
    }

    @Test
    void aPlantedPageIsAnInjectionFindingAndTheTextAppearsNowhere() {
        Map<String, Object> v = run("injected-page");
        assertThat(incidents(v, "INJECTION"))
                .singleElement()
                .satisfies(i -> assertThat(i).containsEntry("severity", "high"));
        assertThat(v.toString()).doesNotContain(Scenarios.PLANTED_MARKER);
        assertThat(registry.views().toString()).doesNotContain(Scenarios.PLANTED_MARKER);
    }

    @Test
    void refusedAccessIsAWarningAndAnUnknownToolIsInfo() {
        Map<String, Object> v = run("access-refused");
        assertThat(incidents(v, "ACCESS"))
                .extracting(i -> i.get("severity"))
                .contains("warn", "info");
        @SuppressWarnings("unchecked")
        Map<String, Object> access =
                (Map<String, Object>) ((Map<String, Object>) v.get("security")).get("access");
        assertThat(((Number) access.get("refused")).intValue()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void everyScenarioRunsToTheEndWithoutAProviderAndLeavesARunBehind() {
        for (String n : Scenarios.names()) {
            RunRegistry fresh = new RunRegistry();
            Scenarios.runInto(fresh, "sim", Scenarios.get(n), "all-" + n, dir);
            RunState state = fresh.find("sim", Ids.traceId("all-" + n)).orElseThrow();
            assertThat(state.view().get("status")).as(n).isIn("finished", "failed");
        }
    }

    @Test
    void theNamesGivenOnTheCommandLineAreSplitAndChecked() {
        assertThat(Scenarios.parse("flaky-tool, trifecta"))
                .extracting(Scenarios.Scenario::name)
                .containsExactly("flaky-tool", "trifecta");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Scenarios.parse("flaky-tool,bogus"))
                .hasMessageContaining("bogus");
        assertThat(Scenarios.parse(null)).isEmpty();
        assertThat(Scenarios.parse("  ")).isEmpty();
    }

    @Test
    void theStudioSimulationHasNoScenarioAndItsHelpStillListsItsOptions() {
        assertThat(Scenarios.names()).doesNotContain("studio");
        assertThat(Simulate.helpText())
                .contains("--fabric")
                .contains("--embedded")
                .contains("--runs")
                .contains("--scenario");
    }
}
