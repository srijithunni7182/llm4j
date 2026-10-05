package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * V4.2b: the report's graph for every script in the repository still has the nodes and edges it had before the graph
 * builder was shared. The old output was recorded from the code before the change ({@code parity/old-workflow-graphs.json}).
 *
 * <p>Only these differences are allowed: a loop's exit edge is labelled {@code done}; a statement that used to be a
 * generic {@code statement} node has its own kind; a rewind draws its way back to its checkpoint; handler blocks are
 * nodes after the main path. A {@code for each} or
 * {@code guardrail} with a body is the one case where ids of later nodes move, because their bodies now have nodes of
 * their own; those workflows are named here so a new one cannot slip in unnoticed.
 */
class WorkflowGraphParityTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    /** Workflows whose ids move because a for-each or guardrail body now has nodes. */
    private static final Set<String> BODIES_NOW_HAVE_NODES = Set.of(
            "examples/getviral/src/main/resources/getviral/getviral.loom#GetViral",
            "loom/ai-agent4j-loom/src/test/resources/docs/readme_examples.loom#Main",
            "loom/ai-agent4j-loom/src/test/resources/graph/all_statements.loom#Everything",
            "loom/ai-agent4j-loom/src/test/resources/graph/handlers.loom#Handlers",
            "loom/ai-agent4j-loom/src/test/resources/tier2-test.loom#Tier2Test");

    private static JsonNode golden() throws Exception {
        try (InputStream in = WorkflowGraphParityTest.class.getResourceAsStream("/parity/old-workflow-graphs.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static WorkflowDef workflow(String key) throws Exception {
        String[] parts = key.split("#");
        LoomScript script = new LoomLoader().load(REPO.resolve(parts[0]).toString());
        return script.getWorkflows().stream().filter(w -> w.getName().equals(parts[1])).findFirst().orElseThrow();
    }

    private static boolean hasBodiesThatNowHaveNodes(WorkflowDef workflow) {
        boolean[] found = {false};
        StatementWalker.walk(workflow.getStatements(), s -> {
            if (s instanceof ForEachStmt f && !f.getBody().isEmpty()) {
                found[0] = true;
            }
            if (s instanceof GuardrailStmt g && !g.getBody().isEmpty()) {
                found[0] = true;
            }
        });
        return found[0];
    }

    @Test
    void everyWorkflowInTheRepositoryKeepsItsOldNodesAndEdges() throws Exception {
        JsonNode golden = golden();
        assertThat(golden.size()).as("workflows recorded before the change").isGreaterThan(50);
        List<String> problems = new ArrayList<>();
        Set<String> moved = new TreeSet<>();

        Iterator<Map.Entry<String, JsonNode>> entries = golden.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            WorkflowDef def = workflow(entry.getKey());
            WorkflowGraph graph = WorkflowGraph.of(def);
            if (hasBodiesThatNowHaveNodes(def)) {
                moved.add(entry.getKey());
                continue;
            }
            compare(entry.getKey(), entry.getValue(), graph, problems);
        }

        assertThat(problems).isEmpty();
        assertThat(moved).isEqualTo(new TreeSet<>(BODIES_NOW_HAVE_NODES));
    }

    private static void compare(String key, JsonNode old, WorkflowGraph graph, List<String> problems) {
        Map<String, WorkflowTrace.Node> nodes = new java.util.HashMap<>();
        graph.nodes().forEach(n -> nodes.put(n.id(), n));
        Set<String> oldIds = new java.util.HashSet<>();
        for (JsonNode o : old.get("nodes")) {
            String id = o.get("id").asText();
            oldIds.add(id);
            WorkflowTrace.Node now = nodes.get(id);
            if (now == null) {
                problems.add(key + ": node " + id + " is gone");
                continue;
            }
            boolean generic = o.get("kind").asText().equals("statement");
            if (!generic && !now.kind().equals(o.get("kind").asText())) {
                problems.add(key + ": " + id + " kind " + o.get("kind").asText() + " -> " + now.kind());
            }
            if (!generic && !now.label().equals(o.get("label").asText())) {
                problems.add(key + ": " + id + " label " + o.get("label").asText() + " -> " + now.label());
            }
            if (!java.util.Objects.equals(now.agent(), o.hasNonNull("agent") ? o.get("agent").asText() : null)) {
                problems.add(key + ": " + id + " agent changed");
            }
            if (!java.util.Objects.equals(now.bound(), o.hasNonNull("bound") ? o.get("bound").asInt() : null)) {
                problems.add(key + ": " + id + " bound changed");
            }
        }
        List<String> now = graph.edges().stream().map(WorkflowGraphParityTest::edge).toList();
        for (JsonNode e : old.get("edges")) {
            String oldEdge = edge(e.get("from").asText(), e.get("to").asText(), e.hasNonNull("label") ? e.get("label").asText() : null);
            String withDone = edge(e.get("from").asText(), e.get("to").asText(), e.hasNonNull("label") ? e.get("label").asText() : "done");
            if (!now.contains(oldEdge) && !now.contains(withDone)) {
                problems.add(key + ": edge " + oldEdge + " is gone");
            }
        }
        for (WorkflowTrace.Edge e : graph.edges()) {
            boolean inOld = old.get("edges").toString().contains("\"from\":\"" + e.from() + "\",\"to\":\"" + e.to() + "\"");
            boolean touchesNew = !oldIds.contains(e.from()) || !oldIds.contains(e.to());
            boolean wayBack = "rewind".equals(e.label());
            if (!inOld && !touchesNew && !wayBack) {
                problems.add(key + ": unexpected new edge " + edge(e.from(), e.to(), e.label()));
            }
        }
    }

    private static String edge(WorkflowTrace.Edge e) {
        return edge(e.from(), e.to(), e.label());
    }

    private static String edge(String from, String to, String label) {
        return from + "->" + to + (label == null ? "" : ":" + label);
    }
}
