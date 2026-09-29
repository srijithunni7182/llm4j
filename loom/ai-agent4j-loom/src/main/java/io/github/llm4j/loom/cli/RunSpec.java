package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything needed to run (or resume) a workflow from its run directory alone — saved as
 * {@code <run dir>/run.json} so that {@code weave resume} or {@code weave tick} in a fresh process, hours
 * later, can rebuild the run.
 */
record RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
               Long maxCalls, String maxCost, String prices, String store, boolean lenient, String trace) {

    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store, boolean lenient) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, null);
    }

    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, false, null);
    }

    static final String FILE = "run.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    RunSpec {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    void write(Path runDir) {
        try {
            Files.createDirectories(runDir);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("script", script);
            m.put("loot", loot);
            m.put("workflow", workflow);
            m.put("inputs", new LinkedHashMap<>(inputs));
            m.put("maxTokens", maxTokens);
            m.put("maxCalls", maxCalls);
            m.put("maxCost", maxCost);
            m.put("prices", prices);
            m.put("store", store);
            m.put("lenient", lenient);
            m.put("trace", trace);
            Files.writeString(runDir.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + runDir.resolve(FILE), e);
        }
    }

    @SuppressWarnings("unchecked")
    static RunSpec read(Path runDir) {
        Path file = runDir.resolve(FILE);
        if (!Files.exists(file)) throw new IllegalArgumentException(runDir + " is not a run directory (no " + FILE + ")");
        try {
            Map<String, Object> m = JSON.readValue(file.toFile(), Map.class);
            return new RunSpec((String) m.get("script"), (String) m.get("loot"), (String) m.get("workflow"),
                    (Map<String, String>) m.get("inputs"), number(m.get("maxTokens")), number(m.get("maxCalls")),
                    (String) m.get("maxCost"), (String) m.get("prices"), (String) m.get("store"),
                    Boolean.TRUE.equals(m.get("lenient")), (String) m.get("trace"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static Long number(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
