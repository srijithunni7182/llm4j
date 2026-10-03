package io.github.llm4j.eval.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Re-renders the dashboard (and the JUnit/Markdown/CSV outputs) from saved {@code
 * eval4j-report.json} files. Give it several reports to merge the runs of a multi-module build into
 * one dashboard.
 *
 * <pre>
 * java -cp eval4j.jar:... io.github.llm4j.eval.report.EvalReportCli \
 *     [--out DIR] [--history FILE] REPORT.json [REPORT.json ...]
 * </pre>
 *
 * {@code --out} defaults to the first report's directory; {@code --history} is an optional {@code
 * eval4j-history.jsonl} to draw trends and the "since previous run" section from.
 */
public final class EvalReportCli {

    private EvalReportCli() {}

    public static void main(String[] args) throws IOException {
        Path out = null;
        Path history = null;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(value(args, ++i, "--out"));
                case "--history" -> history = Path.of(value(args, ++i, "--history"));
                case "-h", "--help" -> {
                    System.out.println(
                            "usage: EvalReportCli [--out DIR] [--history FILE] REPORT.json [REPORT.json ...]");
                    return;
                }
                default -> inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.isEmpty()) {
            System.err.println("eval4j: give at least one eval4j-report.json");
            System.exit(2);
        }
        EvalReportWriter.RunInfo run = merge(read(inputs));
        Path dir = out != null ? out : parent(inputs.get(0));
        List<HistoryEntry> prior =
                history == null ? List.of() : new FileSystemScoreHistory(history).load();
        EvalReportWriter.write(dir, run, prior, Map.of());
        System.out.println("eval4j: wrote dashboard to " + dir.resolve(EvalReportWriter.HTML_FILE));
    }

    static List<EvalReportWriter.RunInfo> read(List<Path> files) throws IOException {
        ObjectMapper mapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<EvalReportWriter.RunInfo> runs = new ArrayList<>();
        for (Path f : files) {
            runs.add(mapper.readValue(Files.readAllBytes(f), EvalReportWriter.RunInfo.class));
        }
        return runs;
    }

    /** One run covering all inputs: earliest start, latest end, concatenated records and tests. */
    static EvalReportWriter.RunInfo merge(List<EvalReportWriter.RunInfo> runs) {
        if (runs.size() == 1) {
            return runs.get(0);
        }
        List<EvalRecord> records = new ArrayList<>();
        List<TestOutcome> tests = new ArrayList<>();
        String start = null;
        String end = null;
        String sha = null;
        for (EvalReportWriter.RunInfo r : runs) {
            records.addAll(r.records() == null ? List.of() : r.records());
            tests.addAll(r.tests() == null ? List.of() : r.tests());
            start =
                    start == null || (r.startedAt() != null && r.startedAt().compareTo(start) < 0)
                            ? r.startedAt()
                            : start;
            end =
                    end == null || (r.endedAt() != null && r.endedAt().compareTo(end) > 0)
                            ? r.endedAt()
                            : end;
            sha = sha == null ? r.gitSha() : sha;
        }
        return new EvalReportWriter.RunInfo(
                "merged-" + runs.get(0).runId(), start, end, sha, records, tests);
    }

    private static Path parent(Path file) {
        Path p = file.toAbsolutePath().getParent();
        return p == null ? Path.of(".") : p;
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }
}
