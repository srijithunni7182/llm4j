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
               Long maxCalls, String maxCost, String prices, String store, boolean lenient, String trace,
               boolean simulate, Map<String, Object> forkOf, String stopAt, Integer maxRewinds,
               String promptsDir, Map<String, String> promptPins) {

    /** As a run with no prompt folder or pins of its own. */
    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store, boolean lenient, String trace,
            boolean simulate, Map<String, Object> forkOf, String stopAt, Integer maxRewinds) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, trace, simulate, forkOf, stopAt, maxRewinds, null, null);
    }

    /** As a run that is real, not a fork, and runs to its end. */
    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store, boolean lenient, String trace) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, trace, false, null, null, null);
    }

    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store, boolean lenient) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, null);
    }

    RunSpec(String script, String loot, String workflow, Map<String, String> inputs, Long maxTokens,
            Long maxCalls, String maxCost, String prices, String store) {
        this(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, false, null);
    }

    RunSpec withStopAt(String point) {
        return new RunSpec(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, trace, simulate, forkOf, point, maxRewinds, promptsDir, promptPins);
    }

    RunSpec withMaxRewinds(Integer max) {
        return new RunSpec(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, trace, simulate, forkOf, stopAt, max, promptsDir, promptPins);
    }

    /** The same run with the prompt folder and pins of {@code settings}, kept so a resumed or forked run uses the same prompts. */
    RunSpec withPrompts(io.github.llm4j.loom.prompt.PromptSettings settings) {
        return new RunSpec(script, loot, workflow, inputs, maxTokens, maxCalls, maxCost, prices, store, lenient, trace, simulate, forkOf, stopAt, maxRewinds,
                settings == null || settings.dir() == null ? null : settings.dir().toAbsolutePath().toString(),
                settings == null ? null : settings.pins());
    }

    /** The prompt settings this run was started with. */
    io.github.llm4j.loom.prompt.PromptSettings prompts() {
        return new io.github.llm4j.loom.prompt.PromptSettings(promptsDir == null ? null : Path.of(promptsDir), promptPins);
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
            if (simulate) m.put("simulate", true);
            if (forkOf != null) m.put("forkOf", forkOf);
            if (maxRewinds != null) m.put("maxRewinds", maxRewinds);
            if (promptsDir != null) m.put("promptsDir", promptsDir);
            if (promptPins != null && !promptPins.isEmpty()) m.put("promptPins", new LinkedHashMap<>(promptPins));
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
                    Boolean.TRUE.equals(m.get("lenient")), (String) m.get("trace"), Boolean.TRUE.equals(m.get("simulate")),
                    (Map<String, Object>) m.get("forkOf"), null, m.get("maxRewinds") instanceof Number n ? n.intValue() : null,
                    (String) m.get("promptsDir"), (Map<String, String>) m.get("promptPins"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static Long number(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
