package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Saved evaluation results to compare later runs against. A baseline is saved with the name of
 * whoever approves it and a written reason, is never replaced (a later one is added beside it), and
 * a comparison shows the current baseline <em>and the one before it</em>, so a slow decline across
 * releases cannot hide behind a re-baseline. Re-baselining is never automatic.
 */
public final class Baselines {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** One saved baseline. {@code cases} maps {@code target · label} to whether it passed. */
    public record Baseline(
            String id,
            String savedAt,
            String approver,
            String why,
            String datasetVersion,
            String datasetHash,
            Double passRate,
            Map<String, Boolean> cases) {}

    private Baselines() {}

    static Path folder(Path project) {
        return project.resolve("eval").resolve("baselines");
    }

    /**
     * Saves the run as a new baseline under {@code project/eval/baselines}.
     *
     * @return the file written
     * @throws IllegalArgumentException when there is no approver or reason, or the run is a mock
     */
    public static Path save(
            Path project,
            DatasetIdentity identity,
            EvalReport.Run run,
            String approver,
            String why,
            Instant at) {
        if (approver == null || approver.isBlank())
            throw new IllegalArgumentException("a baseline needs an approver: --approver <name>");
        if (why == null || why.isBlank())
            throw new IllegalArgumentException("a baseline needs a written reason: --why \"...\"");
        if (run.mock())
            throw new IllegalArgumentException("a mock run judged nothing, so it cannot be a baseline");
        Map<String, Boolean> cases = passed(run);
        long decisive = run.results().stream().filter(r -> r.status() != Status.UNJUDGED).count();
        long pass = run.results().stream().filter(r -> r.status() == Status.PASS).count();
        Path dir = folder(project);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(STAMP.format(at) + ".json");
            for (int n = 2; Files.exists(file); n++)
                file = dir.resolve(STAMP.format(at) + "-" + n + ".json");
            Baseline b =
                    new Baseline(
                            file.getFileName().toString().replace(".json", ""),
                            at.toString(),
                            approver.strip(),
                            why.strip(),
                            identity.declared(),
                            identity.hash(),
                            decisive == 0 ? null : (double) pass / decisive,
                            cases);
            Files.writeString(file, JSON.writeValueAsString(b), java.nio.file.StandardOpenOption.CREATE_NEW);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("cannot save the baseline: " + e.getMessage(), e);
        }
    }

    /** The saved baselines, oldest first. */
    public static List<Baseline> load(Path project) {
        Path dir = folder(project);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<Baseline> out = new ArrayList<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                out.add(JSON.readValue(f.toFile(), Baseline.class));
            out.sort(Comparator.comparing(Baseline::savedAt).thenComparing(Baseline::id));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the baselines: " + e.getMessage(), e);
        }
    }

    /** Why the current baseline cannot be compared with this dataset, or empty when it can. */
    public static Optional<String> mismatch(List<Baseline> saved, DatasetIdentity now) {
        if (saved.isEmpty()) return Optional.empty();
        Baseline current = saved.get(saved.size() - 1);
        if (now.hash().equals(current.datasetHash())) return Optional.empty();
        return Optional.of(
                "the baseline "
                        + current.id()
                        + " was taken on a different dataset ("
                        + (current.datasetVersion() == null ? "no version declared" : "version " + current.datasetVersion())
                        + ", "
                        + current.datasetHash()
                        + "; this one is "
                        + now.describe()
                        + "). Results on different datasets are not comparable. Re-baseline, or compare anyway with"
                        + " --allow-version-change \"<why>\" (the reason is recorded in the report).");
    }

    /** The lines that compare a run with the current and the previous baseline. */
    public static List<String> compare(List<Baseline> saved, EvalReport.Run run, DatasetIdentity now) {
        if (saved.isEmpty())
            return List.of(
                    "Baseline: no baseline saved yet (save one with --save-baseline --approver <name> --why \"...\" on a run you trust).");
        List<String> l = new ArrayList<>();
        Baseline current = saved.get(saved.size() - 1);
        long decisive = run.results().stream().filter(r -> r.status() != Status.UNJUDGED).count();
        long pass = run.results().stream().filter(r -> r.status() == Status.PASS).count();
        Double nowRate = decisive == 0 ? null : (double) pass / decisive;
        l.add("Pass rate now: " + pct(nowRate) + " (" + pass + " of " + decisive + " judged runs).");
        l.add(line("Current baseline", current));
        if (saved.size() > 1) l.add(line("Previous baseline", saved.get(saved.size() - 2)));
        else l.add("Previous baseline: none (this is the first).");
        if (nowRate != null && current.passRate() != null && !current.datasetHash().equals(now.hash()))
            l.add("(the dataset differs from the baseline's: " + now.describe() + ")");
        Map<String, Boolean> nowCases = passed(run);
        List<String> regressed = new ArrayList<>();
        List<String> improved = new ArrayList<>();
        nowCases.forEach(
                (name, ok) -> {
                    Boolean was = current.cases().get(name);
                    if (was == null) return;
                    String shown = name.substring(name.indexOf(" · ") + 3);
                    if (was && !ok) regressed.add(shown);
                    if (!was && ok) improved.add(shown);
                });
        l.add("Cases that regressed since the current baseline: " + (regressed.isEmpty() ? "none" : String.join(", ", regressed)) + ".");
        l.add("Cases that improved: " + (improved.isEmpty() ? "none" : String.join(", ", improved)) + ".");
        return l;
    }

    private static String line(String what, Baseline b) {
        return what + " " + b.id() + ": " + pct(b.passRate()) + ", approved by " + b.approver() + ": " + b.why();
    }

    private static String pct(Double rate) {
        return rate == null ? "n/a" : Math.round(rate * 100) + "%";
    }

    /** Whether each case passed (every run of it passed), by {@code target · label}. */
    private static Map<String, Boolean> passed(EvalReport.Run run) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (ScenarioResult r : run.results()) {
            if (r.status() == Status.UNJUDGED) continue;
            out.merge(r.target() + " · " + r.label(), r.status() == Status.PASS, (a, b) -> a && b);
        }
        return out;
    }
}
