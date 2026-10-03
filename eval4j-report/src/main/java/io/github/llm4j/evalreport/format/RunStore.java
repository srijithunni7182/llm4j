package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.evalreport.format.model.RunMeta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory of bundles ({@code <root>/runs/<runId>}). Listing reads only each {@code run.json}.
 */
public final class RunStore {

    private final Path root;
    private final java.util.Map<String, Path> dirs = new java.util.HashMap<>();

    public RunStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** Runs in the store, oldest first. A directory without a readable run.json is skipped. */
    public List<RunMeta> list() {
        Path runs = root.resolve("runs");
        List<RunMeta> out = new ArrayList<>();
        if (!Files.isDirectory(runs)) {
            return out;
        }
        try (Stream<Path> s = Files.list(runs)) {
            for (Path dir : (Iterable<Path>) s.sorted()::iterator) {
                Path f = dir.resolve("run.json");
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                try {
                    JsonNode n = RunBundleReader.MAPPER.readTree(f.toFile());
                    RunMeta meta = RunBundleReader.meta(n);
                    out.add(meta);
                    dirs.put(meta.runId(), dir);
                } catch (IOException e) {
                    // an unreadable header is not a run
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing((RunMeta m) -> m.startedAt() == null ? "" : m.startedAt()));
        return out;
    }

    /**
     * Runs as the report sees them, oldest first: the bundles of one build (same {@code groupId})
     * collapse into a single run whose id is the group id.
     */
    public List<RunMeta> listRuns() {
        List<RunMeta> all = list();
        java.util.Map<String, List<RunMeta>> groups = new java.util.LinkedHashMap<>();
        List<RunMeta> out = new ArrayList<>();
        for (RunMeta r : all) {
            if (r.groupId() == null) {
                out.add(r);
            } else {
                groups.computeIfAbsent(r.groupId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (var en : groups.entrySet()) {
            List<RunMeta> g = en.getValue();
            if (g.size() == 1) {
                out.add(g.get(0));
                continue;
            }
            out.add(
                    new RunMeta(
                            en.getKey(),
                            en.getKey(),
                            g.get(0).runNumber(),
                            g.stream().anyMatch(m -> !"COMPLETE".equals(m.status()))
                                    ? "PARTIAL"
                                    : "COMPLETE",
                            g.get(0).startedAt(),
                            g.get(g.size() - 1).endedAt(),
                            g.get(0).project(),
                            g.get(0).branch(),
                            g.get(0).commit(),
                            g.get(0).source(),
                            g.get(0).profile(),
                            g.get(0).env(),
                            g.get(0).metrics(),
                            g.get(0).summary()));
        }
        out.sort(Comparator.comparing((RunMeta m) -> m.startedAt() == null ? "" : m.startedAt()));
        return out;
    }

    /** Loads a run by id; an id that is a group id loads and merges every bundle of the group. */
    public RunBundle load(String runId, boolean strict) {
        list();
        if (dirs.containsKey(runId)) {
            return RunBundleReader.read(dirOf(runId), strict);
        }
        List<RunBundle> parts = new ArrayList<>();
        for (RunMeta m : list()) {
            if (runId.equals(m.groupId())) {
                parts.add(RunBundleReader.read(dirOf(m.runId()), strict));
            }
        }
        if (parts.isEmpty()) {
            return RunBundleReader.read(dirOf(runId), strict);
        }
        return parts.size() == 1 ? parts.get(0) : BundleMerger.merge(runId, parts);
    }

    public Path dirOf(String runId) {
        if (!dirs.containsKey(runId)) {
            list();
        }
        return dirs.getOrDefault(runId, root.resolve("runs").resolve(runId));
    }
}
