package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.TraceEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V8: the live trace. */
class TraceTest {

    @TempDir
    Path dir;

    static final String SCRIPT = """
            agent Researcher { model: "m" tools: [Lookup] approve: [Lookup] }
            workflow Main() { delegate "find the answer" to Researcher -> out }
            """;

    Harness harness() {
        Harness h = new Harness(dir).answers(Harness.call("Lookup", "{\"q\": \"x\"}"), Harness.done("forty-two"));
        h.tools.register("Lookup", Harness.recording("Lookup", new ArrayList<>(), "the answer is 42"));
        return h;
    }

    @Test
    void v8_1_andV8_3_eventsArriveInOrderWithAgentAndStep() {
        Harness h = harness();
        h.ready(SCRIPT).executeWorkflow("Main", Map.of());
        List<String> types = h.trace.stream().map(TraceEvent::type).toList();
        assertThat(types).containsSubsequence(TraceEvent.DELEGATE_START, TraceEvent.THOUGHT, TraceEvent.ACTION,
                TraceEvent.APPROVAL, TraceEvent.APPROVAL, TraceEvent.OBSERVATION, TraceEvent.DELEGATE_END);
        assertThat(h.trace).filteredOn(t -> !t.type().equals(TraceEvent.APPROVAL))
                .allSatisfy(t -> {
                    assertThat(t.agent()).isEqualTo("Researcher");
                    assertThat(t.step()).isEqualTo("Main/s0");
                    assertThat(t.at()).isNotNull();
                });
        assertThat(h.trace).filteredOn(t -> t.type().equals(TraceEvent.ACTION)).first()
                .satisfies(t -> assertThat(t.text()).startsWith("Lookup ").contains("\"q\""));
        assertThat(h.trace).filteredOn(t -> t.type().equals(TraceEvent.OBSERVATION)).first()
                .satisfies(t -> assertThat(t.text()).isEqualTo("the answer is 42"));
        assertThat(h.trace).filteredOn(t -> t.type().equals(TraceEvent.DELEGATE_END)).first()
                .satisfies(t -> {
                    assertThat(t.text()).isEqualTo("forty-two");
                    assertThat(t.data()).containsEntry("calls", 2).containsEntry("tokens", 30);
                });
        assertThat(h.trace).filteredOn(t -> t.type().equals(TraceEvent.APPROVAL))
                .extracting(TraceEvent::text).anySatisfy(t -> assertThat(t).startsWith("approval requested: Lookup"));
    }

    @Test
    void aFailingListenerDoesntStopTheRun() {
        Harness h = harness();
        var e = h.executor(SCRIPT, x -> x.addTraceListener(ev -> { throw new IllegalStateException("boom"); }));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(e.getContext().getVariable("out")).isEqualTo("forty-two");
    }

    String weave(String trace, ByteArrayOutputStream out, ByteArrayOutputStream err) throws Exception {
        Path f = dir.resolve("s.loom");
        Files.writeString(f, SCRIPT.replace("tools: [Lookup] approve: [Lookup]", "tools: [calculator]"));
        Harness h = new Harness(dir).answers(Harness.call("calculator", "{\"expression\": \"6*7\"}"), Harness.done("42"));
        int code = CliProbe.run(f.toFile(), trace, new PrintStream(out, true), new PrintStream(err, true), n -> null, h::client);
        assertThat(code).isZero();
        return err.toString();
    }

    @Test
    void v8_2_weaveRunTracePrintsReadableLinesOnStderr() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String err = weave("text", out, new ByteArrayOutputStream());
        assertThat(err).contains("[Main/s0] Researcher  ▶ find the answer")
                .contains("[Main/s0] Researcher  🔧 calculator")
                .contains("[Main/s0] Researcher  👁 42")
                .contains("[Main/s0] Researcher  ✔ 42");
        assertThat(out.toString()).doesNotContain("🔧");
    }

    @Test
    void v8_2_jsonLinesAndNoTraceWithoutTheFlag() throws Exception {
        String err = weave("json", new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> lines = new ArrayList<>();
        for (String line : err.split("\\R")) if (line.startsWith("{")) lines.add(json.readTree(line));
        assertThat(lines).extracting(n -> n.get("type").asText()).contains("delegate_start", "action", "observation", "delegate_end");
        assertThat(lines).allSatisfy(n -> assertThat(n.get("step").asText()).isEqualTo("Main/s0"));

        assertThat(weave(null, new ByteArrayOutputStream(), new ByteArrayOutputStream())).doesNotContain("🔧").doesNotContain("\"type\"");

        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        Path f = dir.resolve("s.loom");
        assertThat(CliProbe.run(f.toFile(), "xml", new PrintStream(new ByteArrayOutputStream()), new PrintStream(err2, true),
                n -> null, m -> null)).isEqualTo(2);
        assertThat(err2.toString()).contains("--trace takes text or json");
    }

    @Test
    void longTextsAreCut() throws Exception {
        Harness h = new Harness(dir).answers(Harness.done("x".repeat(1000)));
        Path f = dir.resolve("long.loom");
        Files.writeString(f, "agent A { model: \"m\" }\nworkflow Main() { delegate \"go\" to A -> out }\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliProbe.run(f.toFile(), "text", new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true), n -> null, h::client);
        assertThat(err.toString()).contains("x".repeat(300) + "…").doesNotContain("x".repeat(301));
    }
}
