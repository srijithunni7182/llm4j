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

    public RunBundle load(String runId, boolean strict) {
        return RunBundleReader.read(dirOf(runId), strict);
    }

    public Path dirOf(String runId) {
        if (!dirs.containsKey(runId)) {
            list();
        }
        return dirs.getOrDefault(runId, root.resolve("runs").resolve(runId));
    }
}
