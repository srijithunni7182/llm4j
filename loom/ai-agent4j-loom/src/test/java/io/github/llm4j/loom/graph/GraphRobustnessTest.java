package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** VR.2 to VR.6: odd inputs give a graph, never a crash. */
class GraphRobustnessTest {

    private final GraphService service = new GraphService();

    @Test
    void anEmptyFileGivesAnEntryWithNoWorkflows(@TempDir Path dir) throws Exception {
        Path empty = Files.writeString(dir.resolve("empty.loom"), "");

        GraphResult result = service.graph(empty);

        assertThat(result.hasGraph()).isTrue();
        assertThat(result.workflows()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void aFileWithOnlyAgentsGivesNoWorkflows(@TempDir Path dir) throws Exception {
        Path agents = Files.writeString(dir.resolve("agents.loom"), "agent A { model: \"m\" }\n");

        GraphResult result = service.graph(agents);

        assertThat(result.hasGraph()).isTrue();
        assertThat(result.workflows()).isEmpty();
    }

    @Test
    void aVeryLongPromptNeverBloatsTheOutput(@TempDir Path dir) throws Exception {
        String payload = "p".repeat(5000);
        Path file = Files.writeString(dir.resolve("long.loom"),
                "agent A { model: \"m\" }\nworkflow W() {\n  delegate \"" + payload + "\" to A -> r\n  note \"" + payload + "\"\n}\n");

        String json = GraphJson.write(service.graph(file));

        assertThat(json.length()).isLessThan(3000);
    }

    @Test
    void twoRunsAtTheSameTimeGiveTheSameOutput() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> GraphJson.write(service.graph(Path.of("samples/boardroom/main.loom")))));
            }
            assertThat(runs.get(0).get()).isEqualTo(runs.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }
}
