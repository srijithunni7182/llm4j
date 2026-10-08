package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V4: knowledge graphs. */
class GraphTest {

    @TempDir
    Path dir;

    static final String ADD = Harness.call("Graph", """
            {"action": "add", "subject": {"id": "asha", "type": "Person"}, "predicate": "WORKS_AT",
             "object": {"id": "acme", "type": "Company", "properties": {"city": "Pune"}}}""");
    static final String QUERY = Harness.call("Graph", "{\"action\": \"query\", \"subjectId\": \"asha\"}");

    static final String SCRIPT = """
            tool Graph { use: knowledge_graph  store: "graphs/people.json" }
            agent Librarian { model: "m" tools: [Graph] }
            workflow Main() { delegate "{task}" to Librarian -> out }
            """;

    @Test
    void v4_1_addThenQueryThroughTheDeclaredTool() {
        Harness h = new Harness(dir).answers(ADD, QUERY, Harness.done("done"));
        h.ready(SCRIPT).executeWorkflow("Main", Map.of("task", "remember and recall"));
        assertThat(h.task(1)).contains("Successfully added relationship: asha -[WORKS_AT]-> acme");
        assertThat(h.task(2)).contains("WORKS_AT").contains("acme");
        assertThat(h.requests.get(0).getMessages().get(0).getContent()).contains("Graph").contains("knowledge graph");
    }

    @Test
    void v4_2_aFileGraphSurvivesAndIsShared() {
        new Harness(dir).answers(ADD, Harness.done("ok")).ready(SCRIPT).executeWorkflow("Main", Map.of("task", "t"));
        assertThat(dir.resolve("graphs/people.json")).exists();

        // a new executor on the same file sees the saved graph; two declarations of the file share one graph
        Harness later = new Harness(dir).answers(
                Harness.call("Graph", "{\"action\": \"add\", \"subject\": {\"id\": \"ravi\", \"type\": \"Person\"}, "
                        + "\"predicate\": \"WORKS_AT\", \"object\": {\"id\": \"globex\", \"type\": \"Company\"}}"),
                Harness.done("added"),
                Harness.call("People", "{\"action\": \"query\", \"entityType\": \"Company\"}"),
                Harness.done("ok"));
        later.ready("""
                tool Graph  { use: knowledge_graph  store: "graphs/people.json" }
                tool People { use: knowledge_graph  store: "graphs/people.json"  read_only: true }
                agent Writer { model: "m" tools: [Graph] }
                agent Reader { model: "m" tools: [People] }
                workflow Main() {
                    delegate "add" to Writer -> a
                    delegate "{task}" to Reader -> out
                }
                """).executeWorkflow("Main", Map.of("task", "find companies"));
        assertThat(later.task(3)).contains("acme").contains("globex");
    }

    @Test
    void v4_3_readOnlyGraphsRefuseAddsAndCorruptFilesFailTheLoad() throws Exception {
        Harness h = new Harness(dir).answers(ADD.replace("\"Graph\"", "\"Graph\""), Harness.call("Graph", "{\"action\": \"delete\"}"), Harness.done("ok"));
        h.ready(SCRIPT.replace("store: \"graphs/people.json\"", "store: memory  read_only: true"))
                .executeWorkflow("Main", Map.of("task", "t"));
        assertThat(h.task(1)).contains("Error: Graph is read-only");
        assertThat(h.task(2)).contains("Error: action must be \"query\", got delete");

        Files.createDirectories(dir.resolve("graphs"));
        Files.writeString(dir.resolve("graphs/people.json"), "{\"entities\": [{\"id\": 1}]");
        assertThatThrownBy(() -> new Harness(dir).ready(SCRIPT))
                .hasMessageContaining("line 1: tool Graph: store graphs/people.json can't be read");
        assertThatThrownBy(() -> new Harness(dir).ready("tool G { use: knowledge_graph store: memory read_only: maybe } agent A { model: \"m\" tools: [G] }"))
                .hasMessageContaining("read_only must be true or false");
        assertThatThrownBy(() -> new Harness(dir).ready("tool G { use: knowledge_graph store: \"graphs\" } agent A { model: \"m\" tools: [G] }"))
                .hasMessageContaining("is a directory");
        assertThatThrownBy(() -> new Harness(dir).ready("tool G { use: knowledge_graph } agent A { model: \"m\" tools: [G] }"))
                .hasMessageContaining("needs store:");
    }
}
