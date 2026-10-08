package io.github.llm4j.evalreport.cli;

import io.github.llm4j.evalreport.EvalReport;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.BundleWriter;
import io.github.llm4j.evalreport.format.LegacyV1Importer;
import io.github.llm4j.evalreport.format.RunBundle;
import io.github.llm4j.evalreport.format.RunBundleReader;
import io.github.llm4j.evalreport.format.RunBundleReader.BundleFormatException;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.render.Csv;
import io.github.llm4j.evalreport.render.HtmlRenderer;
import io.github.llm4j.evalreport.render.JUnitXml;
import io.github.llm4j.evalreport.render.MarkdownSummary;
import io.github.llm4j.evalreport.render.StaticRenderer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line: {@code render}, {@code compare}, {@code list}, {@code validate}. Exit codes: 0
 * success, 2 bad usage, 3 unreadable input, 4 unexpected failure. The report never fails a build
 * because of how a run scored (no verdict).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            usage(out);
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (key.equals("no-compare") || key.equals("strict")) {
                    opt.put(key, "true");
                } else if (i + 1 < args.length) {
                    opt.put(key, args[++i]);
                } else {
                    err.println("missing value for " + a);
                    return 2;
                }
            } else {
                opt.putIfAbsent("input", a);
            }
        }
        try {
            Path root =
                    Path.of(opt.getOrDefault("input", opt.getOrDefault("root", "target/eval4j")));
            ReportConfig config =
                    ReportConfig.load(
                            opt.containsKey("config")
                                    ? Path.of(opt.get("config"))
                                    : Path.of("eval4j-report.yaml"));
            RunStore store = new RunStore(root);
            switch (cmd) {
                case "list" -> {
                    List<RunMeta> runs = store.listRuns();
                    for (RunMeta r : runs) {
                        out.println(
                                r.runId()
                                        + "  "
                                        + r.status()
                                        + "  "
                                        + r.startedAt()
                                        + "  "
                                        + (r.branch() == null ? "-" : r.branch()));
                    }
                    return 0;
                }
                case "merge" -> {
                    String group = opt.get("group");
                    if (group == null) {
                        err.println("merge needs --group <groupId>");
                        return 2;
                    }
                    var merged = store.load(group, opt.containsKey("strict"));
                    Path out2 = Path.of(opt.getOrDefault("out", root.toString()));
                    var json = RunBundleReader.MAPPER.createObjectNode();
                    json.put("schemaVersion", 1);
                    json.put("format", "eval4j-run");
                    json.put("runId", merged.run().runId() + "-merged");
                    json.put("status", merged.run().status());
                    json.put("startedAt", merged.run().startedAt());
                    if (merged.run().endedAt() != null) {
                        json.put("endedAt", merged.run().endedAt());
                    }
                    json.set(
                            "project",
                            RunBundleReader.MAPPER
                                    .createObjectNode()
                                    .put("name", String.valueOf(merged.run().project())));
                    json.set("source", merged.run().source());
                    json.set("profile", merged.run().profile());
                    json.set("env", merged.run().env());
                    json.set("metrics", RunBundleReader.MAPPER.valueToTree(merged.run().metrics()));
                    var asRun = RunBundleReader.meta(json);
                    Path dir =
                            BundleWriter.write(
                                    out2,
                                    new RunBundle(
                                            asRun,
                                            merged.evaluations(),
                                            merged.scenarios(),
                                            merged.tests(),
                                            merged.traces(),
                                            merged.optimizations(),
                                            merged.warnings()),
                                    json);
                    out.println("eval4j-report: merged " + group + " into " + dir);
                    return 0;
                }
                case "import-legacy" -> {
                    String file = opt.get("input");
                    if (file == null) {
                        err.println("import-legacy needs the path of an eval4j-report.json");
                        return 2;
                    }
                    Path dir =
                            LegacyV1Importer.importFile(
                                    Path.of(file),
                                    Path.of(opt.getOrDefault("out", "target/eval4j")));
                    out.println("eval4j-report: imported into " + dir);
                    return 0;
                }
                case "prune" -> {
                    int keep;
                    try {
                        keep = Integer.parseInt(opt.getOrDefault("keep", "50"));
                    } catch (NumberFormatException e) {
                        err.println("--keep must be a number");
                        return 2;
                    }
                    List<RunMeta> all = store.list();
                    int removed = 0;
                    for (int i = 0; i < all.size() - keep; i++) {
                        deleteTree(store.dirOf(all.get(i).runId()));
                        removed++;
                    }
                    out.println(
                            "eval4j-report: removed "
                                    + removed
                                    + " run(s), kept "
                                    + Math.min(keep, all.size()));
                    return 0;
                }
                case "validate" -> {
                    for (RunMeta r : store.list()) {
                        var b = store.load(r.runId(), true);
                        out.println(
                                r.runId()
                                        + ": "
                                        + b.evaluations().size()
                                        + " evaluations, "
                                        + b.warnings().size()
                                        + " warnings");
                    }
                    return 0;
                }
                case "render", "compare" -> {
                    ReportModel m =
                            EvalReport.build(
                                    store,
                                    opt.get("run"),
                                    opt.get("baseline"),
                                    !opt.containsKey("no-compare"),
                                    config);
                    Path outDir =
                            Path.of(opt.getOrDefault("out", root.resolve("report").toString()));
                    Files.createDirectories(outDir);
                    write(outDir.resolve("index.html"), HtmlRenderer.render(m));
                    Path stat = outDir.resolve("static");
                    Files.createDirectories(stat);
                    write(stat.resolve("index.html"), StaticRenderer.render(m));
                    write(stat.resolve(StaticRenderer.CSS_NAME), StaticRenderer.css());
                    write(outDir.resolve("summary.md"), MarkdownSummary.summary(m));
                    write(outDir.resolve("junit.xml"), JUnitXml.render(m));
                    write(outDir.resolve("evaluations.csv"), Csv.render(m));
                    if (m.compare() != null) {
                        write(outDir.resolve("compare.md"), MarkdownSummary.compare(m, 20));
                    }
                    out.println("eval4j-report: wrote " + outDir.resolve("index.html"));
                    return 0;
                }
                default -> {
                    err.println("unknown command: " + cmd);
                    usage(err);
                    return 2;
                }
            }
        } catch (BundleFormatException | IllegalArgumentException e) {
            err.println("eval4j-report: " + e.getMessage());
            return 3;
        } catch (IOException | RuntimeException e) {
            err.println("eval4j-report: unexpected failure: " + e);
            return 4;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p :
                    (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static void usage(PrintStream o) {
        o.println("usage: eval4j-report <command> [<export-dir>] [options]");
        o.println(
                "  render    build index.html, summary.md, junit.xml, evaluations.csv (and compare.md)");
        o.println("  compare   same as render, always against a baseline");
        o.println("  list      list the runs in the export directory");
        o.println("  validate  read every run strictly and report problems");
        o.println(
                "  merge     --group <id> [--out <dir>]  merge the bundles of one build into one run");
        o.println(
                "  import-legacy <eval4j-report.json> [--out <dir>]  turn a v1 report into a bundle");
        o.println("  prune     --keep <n>  delete all but the newest n runs");
        o.println(
                "options: --run <id> --baseline <id> --no-compare --out <dir> --config <file> --strict");
    }
}
