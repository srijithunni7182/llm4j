package io.github.llm4j.loom.fabric;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.control.LoopActions;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * "Create test" and "Try a change" on Fabric. It does the work with Loom's own code when Loom is on
 * the class path and the run's store is readable here (found by reflection: Fabric does not depend
 * on Loom). When it is not, it says which command to run where the store lives. It never writes
 * into a source repository: the case is returned to the page, or written into the folder it was
 * given.
 */
public final class FabricLoop implements LoopActions {

    /**
     * @param allowed the explicit capture permission ({@code --allow-capture})
     * @param store where the runs' journals are, as this server sees them; null when they live
     *     elsewhere
     * @param folder where a captured case is also written; null to only hand it to the page
     */
    public record Settings(boolean allowed, Path store, Path folder) {
        public static Settings off() {
            return new Settings(false, null, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BRIDGE = "io.github.llm4j.loom.loop.FabricBridge";

    private final Settings settings;
    private final RunRegistry registry;
    private final Path counts;

    public FabricLoop(Settings settings, Path data, RunRegistry registry) {
        this.settings = settings;
        this.registry = registry;
        this.counts = data.resolve("captures.json");
    }

    @Override
    public boolean enabled() {
        return settings.allowed();
    }

    @Override
    public String disabledReason() {
        return "capture shows run content, so it is off until you allow it: start Fabric with --allow-capture";
    }

    @Override
    public Outcome capture(String traceId, String kind, boolean injection, String who) {
        RunState run = registry.findByTrace(traceId).orElse(null);
        if (run == null || run.runId() == null) return Outcome.failed(404, "no such run");
        String runId = run.runId();
        StringBuilder cmd = new StringBuilder("weave capture ").append(runId);
        cmd.append(" --store <where this run's store is>");
        if (kind != null) cmd.append(" --tag incident:").append(kind);
        if (settings.store() == null)
            return Outcome.elsewhere(
                    "This server does not hold the run's journal. Run this where the store lives:",
                    cmd.toString());
        Map<String, Object> r =
                call(
                        "capture",
                        new Class<?>[] {
                            String.class, String.class, String.class, boolean.class, String.class
                        },
                        settings.store().toString(),
                        runId,
                        kind,
                        injection,
                        settings.folder() == null ? null : settings.folder().toString());
        if (r == null)
            return Outcome.elsewhere(
                    "Loom is not installed on this server, so it cannot read a journal. Run this where the store lives:",
                    cmd.toString());
        String message = String.valueOf(r.get("message"));
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            if (message.startsWith("no run "))
                return Outcome.elsewhere(
                        "This server's store does not have that run. Run this where it does:",
                        cmd.toString());
            return Outcome.failed(422, message);
        }
        if (kind != null) count(kind, runId);
        return new Outcome(
                true,
                200,
                message,
                (String) r.get("name"),
                (String) r.get("text"),
                (String) r.get("savedTo"),
                null);
    }

    @Override
    public Outcome tryChange(
            String traceId,
            String at,
            Map<String, String> sets,
            Map<String, String> answers,
            String who) {
        RunState run = registry.findByTrace(traceId).orElse(null);
        if (run == null || run.runId() == null) return Outcome.failed(404, "no such run");
        String runId = run.runId();
        StringBuilder cmd = new StringBuilder("weave whatif ").append(runId);
        cmd.append(" --store <where this run's store is> --at ")
                .append(at == null ? "<where>" : at);
        sets.forEach((k, v) -> cmd.append(" --set ").append(shell(k + "=" + v)));
        answers.forEach((k, v) -> cmd.append(" --answer ").append(shell(k + "=" + v)));
        if (settings.store() == null)
            return Outcome.elsewhere(
                    "This server does not hold the run's journal. Run this where the store lives:",
                    cmd.toString());
        Map<String, Object> r =
                call(
                        "whatIf",
                        new Class<?>[] {
                            String.class, String.class, String.class, Map.class, Map.class
                        },
                        settings.store().toString(),
                        runId,
                        at,
                        sets,
                        answers);
        if (r == null)
            return Outcome.elsewhere(
                    "Loom is not installed on this server. Run this where the store lives:",
                    cmd.toString());
        String message = String.valueOf(r.get("message"));
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            if (message.startsWith("no run "))
                return Outcome.elsewhere(
                        "This server's store does not have that run. Run this where it does:",
                        cmd.toString());
            return Outcome.failed(422, message);
        }
        return new Outcome(true, 200, message, null, (String) r.get("report"), null, null);
    }

    @Override
    public synchronized int testsFor(String kind) {
        return load().getOrDefault(kind, Set.of()).size();
    }

    private static String shell(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(String method, Class<?>[] types, Object... args) {
        Class<?> bridge;
        try {
            bridge = Class.forName(BRIDGE);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        try {
            Method m = bridge.getMethod(method, types);
            return (Map<String, Object>) m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", "Loom on this server cannot do that: " + e.getMessage());
            return out;
        }
    }

    // the incident -> test link: which runs became tests, by kind of incident

    private synchronized void count(String kind, String runId) {
        Map<String, Set<String>> all = load();
        if (!all.computeIfAbsent(kind, k -> new TreeSet<>()).add(runId)) return;
        try {
            Files.createDirectories(counts.getParent());
            Files.writeString(counts, JSON.writeValueAsString(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // the link is a convenience; the capture itself succeeded
        }
    }

    private Map<String, Set<String>> load() {
        Map<String, Set<String>> out = new TreeMap<>();
        if (!Files.isRegularFile(counts)) return out;
        try {
            Map<String, List<String>> raw =
                    JSON.readValue(
                            Files.readString(counts, StandardCharsets.UTF_8),
                            new TypeReference<Map<String, List<String>>>() {});
            raw.forEach((k, v) -> out.put(k, new TreeSet<>(new ArrayList<>(v))));
        } catch (IOException | RuntimeException e) {
            // a damaged file counts as nothing
        }
        return out;
    }
}
