package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine;

/**
 * The one line that tells a developer, when they start a workflow or package it, that review has
 * open findings. It is advisory and never in the way:
 *
 * <ul>
 *   <li>it goes to standard error, as plain text, once per process, and only when something is open
 *   <li>it is worked out on another thread while the command starts, from a cache when the script
 *       and its inputs have not changed, and is dropped if it is not ready within a time cap (1
 *       second unless {@code notice.timeout} says otherwise), so a command never waits for it
 *   <li>a crash, a bad rules file or a slow review at most turns it into one {@code review
 *       unavailable} line, and never changes the command's output, exit code or files
 * </ul>
 *
 * <p>What counts as open is what {@code weave review} lists as open: accepted and baselined
 * findings are counted apart, expired accepts are open, dismissed findings are invisible to the
 * command line and so count as open. {@code notice = high|medium|low|off} in {@code
 * review.properties}, {@code --review-notice} and {@code LOOM_REVIEW_NOTICE} choose the lowest
 * severity that is mentioned, or turn it off.
 */
final class ReviewNotice {

    static final long DEFAULT_CAP_MILLIS = 1000;

    /**
     * The cap when {@code notice.timeout} is not set; tests raise it so a cold JVM is not "slow".
     */
    static volatile long defaultCapMillis = DEFAULT_CAP_MILLIS;

    private static final AtomicBoolean PRINTED = new AtomicBoolean(false);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Thread worker;

    private ReviewNotice(Thread worker) {
        this.worker = worker;
    }

    /** Forgets that a line was printed; for tests, which run many commands in one process. */
    static void reset() {
        PRINTED.set(false);
    }

    /** Starts working out the notice; returns at once. Does nothing when it is off. */
    static ReviewNotice start(File script, WeaveEnv env, String option) {
        long begun = System.nanoTime();
        Thread t =
                new Thread(
                        () -> {
                            try {
                                String line = line(script, env, option, begun);
                                if (line != null && PRINTED.compareAndSet(false, true))
                                    env.err().println(line);
                            } catch (Throwable e) {
                                if (PRINTED.compareAndSet(false, true))
                                    env.err().println("review unavailable (" + reason(e) + ")");
                            }
                        },
                        "weave-review-notice");
        t.setDaemon(true);
        t.start();
        return new ReviewNotice(t);
    }

    /** Waits for the notice (tests); a command never calls this. */
    void await() {
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String reason(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank()
                ? e.getClass().getSimpleName()
                : m.lines().findFirst().orElse("");
    }

    /** The line to print, or null when nothing is open, the notice is off or it came too late. */
    private static String line(File script, WeaveEnv env, String option, long begun)
            throws Exception {
        ReviewSettings settings = ReviewSettings.besides(script.toPath());
        String levelText = option != null ? option : env.env().apply("LOOM_REVIEW_NOTICE");
        if (levelText == null) levelText = settings.value("notice").orElse("low");
        levelText = levelText.strip().toLowerCase(Locale.ROOT);
        if (levelText.equals("off")) return null;
        Optional<Severity> level = Severity.parse(levelText);
        if (level.isEmpty())
            throw new IllegalArgumentException(
                    "notice takes high, medium, low or off, not '" + levelText + "'");
        long cap = capMillis(settings);
        Counts counts = cached(script, settings);
        if (counts == null) {
            counts = compute(script, env);
            store(script, settings, counts);
        }
        if ((System.nanoTime() - begun) / 1_000_000 > cap)
            return null; // too late: skip, never delay
        return format(counts, level.get());
    }

    record Counts(int high, int medium, int low, int info, int accepted, int baselined) {}

    static String format(Counts c, Severity level) {
        int high = c.high();
        int medium = Severity.MEDIUM.atLeast(level) ? c.medium() : 0;
        int low = Severity.LOW.atLeast(level) ? c.low() : 0;
        if (high + medium + low == 0) return null;
        StringBuilder b = new StringBuilder("⚠ weave review: ");
        String sep = "";
        if (high > 0) {
            b.append(high).append(" high");
            sep = " · ";
        }
        if (medium > 0) {
            b.append(sep).append(medium).append(" medium");
            sep = " · ";
        }
        if (low > 0) b.append(sep).append(low).append(" low");
        b.append(" open");
        if (c.accepted() + c.baselined() > 0) {
            b.append(" (");
            if (c.accepted() > 0) b.append(c.accepted()).append(" accepted");
            if (c.accepted() > 0 && c.baselined() > 0) b.append(", ");
            if (c.baselined() > 0) b.append(c.baselined()).append(" baselined");
            b.append(')');
        }
        b.append(
                " — not blocking. Details: weave review   Silence: review.properties notice = off");
        return b.toString();
    }

    private static long capMillis(ReviewSettings settings) {
        String v = settings.value("notice.timeout").orElse(null);
        if (v == null) return defaultCapMillis;
        String t = v.strip().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("ms")) return Long.parseLong(t.substring(0, t.length() - 2).strip());
            if (t.endsWith("s"))
                return (long) (Double.parseDouble(t.substring(0, t.length() - 1).strip()) * 1000);
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "notice.timeout takes a time such as 1s or 500ms, not '" + v + "'");
        }
    }

    /** Reviews the script quietly, the way {@code weave review --format json} would. */
    private static Counts compute(File script, WeaveEnv env) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv quiet =
                env.withStreams(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        ReviewCommand c = new ReviewCommand();
        new CommandLine(c).parseArgs(script.getPath(), "--format", "json", "--no-env");
        int exit = ReviewCommand.review(c, quiet);
        if (exit == 2)
            throw new IllegalStateException(
                    err.toString(StandardCharsets.UTF_8)
                            .lines()
                            .findFirst()
                            .orElse("the review could not run")
                            .replaceFirst("^Error: ", ""));
        JsonNode r = JSON.readTree(out.toString(StandardCharsets.UTF_8));
        int[] n = new int[4];
        for (JsonNode f : r.path("findings")) {
            switch (f.path("severity").asText()) {
                case "high" -> n[0]++;
                case "medium" -> n[1]++;
                case "low" -> n[2]++;
                default -> n[3]++;
            }
        }
        return new Counts(
                n[0], n[1], n[2], n[3], r.path("accepted").size(), r.path("baselined").size());
    }

    // ---- the cache: free when nothing has changed

    private static Path cacheFile(File script, ReviewSettings settings) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Path dir = script.getAbsoluteFile().toPath().getParent();
        add(md, "version", String.valueOf(io.github.llm4j.loom.review.engine.ReviewReport.VERSION));
        add(md, "pack", io.github.llm4j.loom.review.Reviews.corePack().label());
        add(md, "script", Files.readString(script.toPath()));
        for (String name : new String[] {ReviewSettings.FILE_NAME, "review.baseline.json"}) {
            Path f = dir.resolve(name);
            add(md, name, Files.isRegularFile(f) ? Files.readString(f) : "");
        }
        Path prompts = dir.resolve("prompts");
        if (Files.isDirectory(prompts))
            try (var files = Files.walk(prompts)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList())
                    add(md, prompts.relativize(p).toString(), Files.readString(p));
            }
        add(md, "today", java.time.LocalDate.now().toString()); // accepts expire by date
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "weave-review-notice");
        return root.resolve(HexFormat.of().formatHex(md.digest()) + ".txt");
    }

    private static void add(MessageDigest md, String key, String value) {
        md.update(key.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(value.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
    }

    private static Counts cached(File script, ReviewSettings settings) {
        try {
            Path f = cacheFile(script, settings);
            if (!Files.isRegularFile(f)) return null;
            String[] p = Files.readString(f).strip().split(" ");
            return new Counts(
                    Integer.parseInt(p[0]),
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]));
        } catch (Exception e) {
            return null; // a cache that cannot be read is a cache miss
        }
    }

    private static void store(File script, ReviewSettings settings, Counts c) {
        try {
            Path f = cacheFile(script, settings);
            Files.createDirectories(f.getParent());
            Files.writeString(
                    f,
                    c.high()
                            + " "
                            + c.medium()
                            + " "
                            + c.low()
                            + " "
                            + c.info()
                            + " "
                            + c.accepted()
                            + " "
                            + c.baselined());
        } catch (Exception e) {
            // not caching is fine
        }
    }
}
