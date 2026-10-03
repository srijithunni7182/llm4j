package io.github.llm4j.eval.export;

import java.nio.file.Files;
import java.nio.file.Path;

/** Export settings, read from system properties ({@code eval4j.export*}, {@code eval4j.run.*}). */
public record ExportConfig(
        boolean enabled, Path root, String runId, String groupId, String profile, int retain) {

    public static final String EXPORT = "eval4j.export";
    public static final String DIR = "eval4j.export.dir";
    public static final String LEGACY_DIR = "eval4j.report.dir";
    public static final String RETAIN = "eval4j.export.retain";
    public static final String RUN_ID = "eval4j.run.id";
    public static final String GROUP = "eval4j.run.group";
    public static final String PROFILE = "eval4j.profile";

    public static ExportConfig fromSystem() {
        String flag = System.getProperty(EXPORT);
        boolean enabled = flag == null || !flag.equalsIgnoreCase("false");
        String dir = System.getProperty(DIR);
        if (dir == null || dir.isBlank()) {
            String legacy = System.getProperty(LEGACY_DIR);
            if (legacy != null && !legacy.isBlank()) {
                dir = legacy;
            }
        }
        Path root;
        if (dir != null && !dir.isBlank()) {
            root = Path.of(dir);
        } else if (Files.isDirectory(Path.of("target"))) {
            root = Path.of("target", "eval4j");
        } else if (Files.isDirectory(Path.of("build"))) {
            root = Path.of("build", "eval4j");
        } else {
            root = Path.of("eval4j-runs");
        }
        String runId = System.getProperty(RUN_ID);
        if (runId == null || runId.isBlank()) {
            runId = newRunId();
        }
        int retain = 50;
        try {
            retain = Integer.parseInt(System.getProperty(RETAIN, "50"));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        return new ExportConfig(
                enabled,
                root,
                runId,
                blankToNull(System.getProperty(GROUP)),
                System.getProperty(PROFILE, "BUILD").toUpperCase(java.util.Locale.ROOT),
                retain);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Time-ordered id: millisecond timestamp (base36) plus 8 random hex characters. */
    static String newRunId() {
        String time =
                Long.toString(System.currentTimeMillis(), 36).toUpperCase(java.util.Locale.ROOT);
        String rnd = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "R" + time + rnd;
    }
}
