package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** V1.8 and V2.*: graphs across files, call links, agents and diagnostics. */
class GraphServiceTest {

    private final GraphService service = new GraphService();

    private static Path path(String relative) {
        return Path.of(relative).toAbsolutePath().normalize();
    }

    private static Path resource(String relative) {
        return path("src/test/resources/" + relative);
    }

    private static WorkflowGraph workflow(GraphResult result, String name) {
        return result.workflows().stream().filter(w -> w.name().equals(name)).findFirst().orElseThrow();
    }

    private static GraphNode callNode(WorkflowGraph graph) {
        return graph.nodes().stream().filter(n -> n.call() != null).findFirst().orElseThrow();
    }

    private static List<String> fileNames(GraphResult result) {
        return result.files().stream().map(f -> Path.of(f.path()).getFileName().toString()).collect(Collectors.toList());
    }

    @Test
    void aCallLinksToTheFileThatDefinesTheCalledWorkflow() {
        GraphResult result = service.graph(resource("imports/parent.loom"));

        GraphNode call = callNode(workflow(result, "ParentWorkflow"));
        assertThat(call.call()).isEqualTo(new CallLink("ChildWorkflow", resource("imports/child.loom").toString()));
        assertThat(call.unresolved()).isFalse();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void everyWorkflowRecordsItsFileAndEveryFileIsListedWithItsImports() {
        GraphResult result = service.graph(resource("graph/diamond/a.loom"));

        assertThat(fileNames(result)).containsExactly("a.loom", "b.loom", "d.loom", "c.loom");
        assertThat(result.workflows()).extracting(WorkflowGraph::name).containsExactly("A", "B", "D", "C");
        assertThat(result.workflows()).allSatisfy(w -> assertThat(result.files()).extracting(ImportFile::path).contains(w.file()));
        ImportFile a = result.files().get(0);
        assertThat(a.imports()).containsExactly(
                resource("graph/diamond/b.loom").toString(), resource("graph/diamond/c.loom").toString());
        assertThat(result.entry()).isEqualTo(resource("graph/diamond/a.loom").toString());
    }

    @Test
    void aCallToAWorkflowThatIsDefinedNowhereIsUnresolvedAndTheGraphIsStillWhole() {
        GraphResult result = service.graph(resource("graph/unresolved_call.loom"));

        GraphNode call = callNode(workflow(result, "W"));
        assertThat(call.unresolved()).isTrue();
        assertThat(call.call().file()).isNull();
        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.ERROR);
            assertThat(d.line()).isEqualTo(2);
            assertThat(d.message()).contains("call Nowhere");
        });
        assertThat(workflow(result, "W").nodes()).hasSize(3);
    }

    @Test
    void aRecursiveCallIsDrawnOnceAndNeverExpanded() {
        GraphResult result = service.graph(resource("graph/recursive.loom"));

        WorkflowGraph a = workflow(result, "A");
        assertThat(a.nodes()).extracting(GraphNode::id).containsExactly("start", "n1", "end");
        assertThat(callNode(a).call()).isEqualTo(new CallLink("A", resource("graph/recursive.loom").toString()));
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void whenTwoImportsDefineTheSameNameTheFirstInRunOrderWinsAndTheOtherIsReported() {
        GraphResult result = service.graph(resource("graph/duplicate_names/main.loom"));

        GraphNode call = callNode(workflow(result, "Main"));
        assertThat(call.call().file()).isEqualTo(resource("graph/duplicate_names/one.loom").toString());
        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
            assertThat(d.file()).endsWith("two.loom");
            assertThat(d.message()).contains("Shared").contains("one.loom");
        });
    }

    @Test
    void theHarnessAndTheGraphPickTheSameWorkflowWhenNamesClash() throws Exception {
        // run order is what LoomLoader produces: the first match of the merged script is used
        var merged = new io.github.llm4j.loom.execution.LoomLoader()
                .load(resource("graph/duplicate_names/main.loom").toString());
        var runsFirst = merged.getWorkflows().stream().filter(w -> w.getName().equals("Shared")).findFirst().orElseThrow();
        GraphResult result = service.graph(resource("graph/duplicate_names/main.loom"));

        WorkflowGraph winner = result.workflows().stream()
                .filter(w -> w.file().equals(callNode(workflow(result, "Main")).call().file()))
                .findFirst()
                .orElseThrow();
        assertThat(winner.line()).isEqualTo(runsFirst.getLine());
        assertThat(winner.nodes().get(1).attrs()).containsEntry("text", "one");
        assertThat(runsFirst.getStatements().get(0)).isInstanceOf(io.github.llm4j.loom.ast.NoteStmt.class);
        assertThat(((io.github.llm4j.loom.ast.NoteStmt) runsFirst.getStatements().get(0)).getMessage()).isEqualTo("one");
    }

    @Test
    void importCyclesAndBrokenImportsStillProduceTheGraphsThatLoaded() {
        GraphResult cycle = service.graph(resource("imports/circular_a.loom"));
        assertThat(cycle.workflows()).extracting(WorkflowGraph::name).containsExactlyInAnyOrder("A", "B");
        assertThat(cycle.diagnostics()).hasSize(1);

        GraphResult broken = service.graph(resource("graph/syntax_error_import/main.loom"));
        assertThat(broken.workflows()).extracting(WorkflowGraph::name).containsExactly("Main");
        assertThat(broken.diagnostics()).singleElement().satisfies(d -> assertThat(d.line()).isEqualTo(5));
        assertThat(broken.hasGraph()).isTrue();
    }

    @Test
    void aMissingEntryFileGivesNoGraph() {
        GraphResult result = service.graph(resource("graph/nothing_here.loom"));

        assertThat(result.hasGraph()).isFalse();
        assertThat(result.workflows()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
    }

    @Test
    void agentsNamedByNodesAreDescribedWithTheirSettingsAndSourceLine() {
        GraphResult result = service.graph(path("samples/content_factory/main.loom"));

        assertThat(result.agents()).extracting(AgentInfo::name).containsExactly("Researcher", "Copywriter");
        AgentInfo researcher = result.agents().get(0);
        assertThat(researcher.model()).isEqualTo("gemini-3.5-flash");
        assertThat(researcher.source()).isEqualTo(new SourceRef(path("samples/content_factory/primitives.loom").toString(), 3));
        assertThat(researcher.tools()).isEmpty();
        assertThat(researcher.approveAll()).isFalse();
    }

    @Test
    void anAgentThatIsNotDefinedIsReportedAsAWarning() {
        GraphResult result = service.graph(resource("graph/handlers.loom"));
        assertThat(result.diagnostics()).isEmpty(); // Researcher and Writer are defined

        GraphResult missing = service.graph(resource("graph/undefined_agent.loom"));
        assertThat(missing.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
            assertThat(d.message()).contains("Ghost");
            assertThat(d.line()).isEqualTo(2);
        });
    }

    @Test
    void aDecideNodeShowsTheLevelItsDecisionStartsAt() {
        GraphResult result = service.graph(resource("graph/all_statements.loom"));

        GraphNode decide = workflow(result, "Everything").nodes().stream()
                .filter(n -> n.kind().equals("decide")).findFirst().orElseThrow();
        assertThat(decide.attrs()).containsEntry("level", "watch");
    }

    @Test
    void theRunBudgetComesFromTheFirstFileInRunOrderThatDeclaresOne() {
        GraphResult digest = service.graph(path("samples/digest/digest.loom"));
        assertThat(digest.runBudget()).containsEntry("tokens", 200000L).containsEntry("window", "day")
                .containsEntry("whenExhausted", "suspend");

        GraphResult none = service.graph(path("samples/content_factory/main.loom"));
        assertThat(none.runBudget()).isEqualTo(Map.of());
    }
}
