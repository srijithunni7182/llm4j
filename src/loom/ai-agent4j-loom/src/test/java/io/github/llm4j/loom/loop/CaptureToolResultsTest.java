package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.wtest.TestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36e, D11 (G90): a captured case carries what each {@code use} answered, masked, so an
 * integration can be tested without the system; the written case runs with no real call.
 */
class CaptureToolResultsTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            tool Orders { use: http  base_url: "https://api.example.com"  methods: "GET"  allow_paths: "/orders/*" }
            workflow Main(id) {
                use Orders(method = "GET", path = "/orders/1") -> order
                use Orders(method = "GET", path = "/orders/2") -> second
                return order
            }
            """;

    private RunRecord run(String firstResult) throws Exception {
        Path script = Runs.project(dir.resolve("proj"), SCRIPT);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        body.putObject("input").put("id", "1");
        var tools = body.putObject("tools");
        tools.putArray("Orders").add(firstResult).add("order 2 packed");
        return StoreReader.open(Runs.run(dir.resolve("store"), "r1", script, body, Map.of("id", "1")));
    }

    @Test
    void whatEachUseAnsweredIsWrittenIntoToolsInOrderAndTheCasePasses() throws Exception {
        RunRecord run = run("order 1 shipped");

        Capture.Captured c = Capture.capture(run, Capture.Options.defaults(), true);

        assertThat(c.text()).contains("tools:").contains("Orders:").contains("order 1 shipped").contains("order 2 packed");
        assertThat(c.text().indexOf("order 1 shipped")).isLessThan(c.text().indexOf("order 2 packed"));
        var suite = TestRunner.load(run.script(), List.of(c.file()));
        assertThat(suite.problems()).isEmpty();
        var result = TestRunner.run(suite, null, false, false).get(0);
        assertThat(result.failures()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void aSecretAndPersonalDataInAToolResultNeverReachTheWrittenCase() throws Exception {
        String leaked = "owner " + Hostile.PERSONAL.get(0) + " token " + Hostile.SECRETS.get(0);
        RunRecord run = run(leaked);

        Capture.Captured c = Capture.capture(run, Capture.Options.defaults(), true);

        String onDisk = Files.readString(c.file());
        assertThat(onDisk).contains("tools:");
        for (String v : List.of(Hostile.PERSONAL.get(0), Hostile.SECRETS.get(0))) assertThat(onDisk).as(v).doesNotContain(v);
        var suite = TestRunner.load(run.script(), List.of(c.file()));
        assertThat(TestRunner.run(suite, null, false, false).get(0).passed()).isTrue();
    }
}
