package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.evalreport.cli.Main;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** VP.7: a report with twenty workflow graphs of a hundred steps opens and draws its first graph quickly, and carries little extra code. */
class ReportGraphPerfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RENDER = Path.of("src/main/resources/io/github/llm4j/evalreport/render");

    private static String twentyTraces() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int t = 0; t < 20; t++) {
            ArrayNode nodes = MAPPER.createArrayNode();
            ArrayNode edges = MAPPER.createArrayNode();
            ArrayNode path = MAPPER.createArrayNode();
            nodes.add(MAPPER.createObjectNode().put("id", "start").put("kind", "start").put("label", "Start"));
            path.add("start");
            String prev = "start";
            for (int i = 1; i <= 98; i++) {
                String id = "n" + i;
                ObjectNode n = MAPPER.createObjectNode().put("id", id).put("kind", i % 3 == 0 ? "note" : "delegate").put("label", i % 3 == 0 ? "note" : "delegate A");
                if (i % 3 != 0) {
                    n.put("agent", "A");
                    n.set("attrs", MAPPER.createObjectNode().put("retry", 2).put("timeoutMs", 30000));
                }
                nodes.add(n);
                edges.add(MAPPER.createObjectNode().put("from", prev).put("to", id));
                path.add(id);
                prev = id;
            }
            nodes.add(MAPPER.createObjectNode().put("id", "end").put("kind", "end").put("label", "End"));
            edges.add(MAPPER.createObjectNode().put("from", prev).put("to", "end"));
            path.add("end");
            ObjectNode w = MAPPER.createObjectNode().put("name", "W" + t);
            w.putObject("graph").set("nodes", nodes);
            ((ObjectNode) w.get("graph")).set("edges", edges);
            w.set("expectedPath", path);
            w.set("actualPath", path);
            w.putArray("events");
            w.putArray("spend");
            ObjectNode trace = MAPPER.createObjectNode().put("traceId", "t_perf_" + t).put("type", "WORKFLOW");
            trace.set("workflow", w);
            lines.append(MAPPER.writeValueAsString(trace)).append('\n');
        }
        return lines.toString();
    }

    private static Path report(Path tmp) throws Exception {
        Path src = Path.of("src/test/resources/examples/minimal");
        for (String run : new String[] {"previous", "candidate"}) {
            Path dst = tmp.resolve("runs").resolve(run);
            Files.createDirectories(dst);
            try (var files = Files.list(src.resolve(run))) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(tmp.resolve("runs/candidate/traces.jsonl"), twentyTraces(), StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(Main.run(new String[] {"render", tmp.toString()}, new PrintStream(out), new PrintStream(out))).as(out.toString()).isZero();
        return tmp.resolve("report/index.html");
    }

    @Test
    void theGraphCodeAddsLessThanOneHundredAndTwentyKilobytesToTheReport() throws Exception {
        long added = Files.size(RENDER.resolve("graph-render.js")) + Files.size(RENDER.resolve("graph-card.js"));

        assertThat(added).as("graph-render.js plus graph-card.js").isLessThan(120 * 1024);
    }

    @Test
    void twentyGraphsOfAHundredStepsDrawTheFirstOneWithinASecond(@TempDir Path tmp) throws Exception {
        Path html = report(tmp);
        assumeTrue(commandExists("node"), "node is not installed");

        Process process = new ProcessBuilder("node", "src/test/js/report-graph.perf.browser.js", html.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();

        assumeTrue(code != 77, "no browser available: " + output);
        assertThat(code).as(output).isZero();
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
