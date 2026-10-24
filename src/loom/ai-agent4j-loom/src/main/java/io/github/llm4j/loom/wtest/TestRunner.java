package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the test files for a script, checks them, and runs their cases. {@link #load} and {@link
 * #run} are separate so the command can stop after checking.
 */
public final class TestRunner {

    /** How one case came out. */
    public record CaseResult(
            String file,
            String name,
            boolean passed,
            long millis,
            List<String> failures,
            List<String> notes,
            List<String> tags,
            List<String> steps) {}

    /** What the files contain and whether they fit the script. */
    public record Suite(
            Path script,
            LoomScript loaded,
            GraphResult graph,
            List<TestFile> files,
            List<String> problems) {}

    private TestRunner() {}

    /** Reads the script and its test files and checks one against the other. */
    public static Suite load(Path scriptOrNull, List<Path> testPaths) throws Exception {
        List<TestFile> files = new ArrayList<>();
        List<Path> found = new ArrayList<>();
        for (Path p : testPaths) found.addAll(testFiles(p));
        for (Path f : found) files.add(TestFile.read(f));
        Path script = scriptOrNull;
        if (script == null) {
            for (TestFile f : files)
                if (f.script() != null) {
                    script = f.path().toAbsolutePath().getParent().resolve(f.script()).normalize();
                    break;
                }
        }
        if (script == null)
            throw new IllegalArgumentException(
                    "which script? name it on the command line, or put script: in the test file");
        LoomScript loaded = new LoomLoader().load(script.toString());
        checkScript(loaded, script);
        GraphResult graph = new GraphService().graph(script);
        List<TestFile> mine = new ArrayList<>();
        Path target = script.toAbsolutePath().normalize();
        for (TestFile f : files) {
            if (f.script() != null) {
                Path wanted = f.path().toAbsolutePath().getParent().resolve(f.script()).normalize();
                if (!wanted.equals(target) && scriptOrNull != null) continue;
            }
            mine.add(f);
        }
        List<String> problems = new TestChecker(loaded, graph).check(mine);
        return new Suite(script, loaded, graph, mine, problems);
    }

    /**
     * The full load-time check of {@code weave check}, with every model and secret standing in: a
     * script that does not pass cannot be tested, and every case would fail with the same message.
     * A missing key is not a problem under test, because models and tools are scripted.
     */
    private static void checkScript(LoomScript loaded, Path script) {
        HarnessExecutor executor =
                new HarnessExecutor(
                        loaded,
                        new ToolRegistry(),
                        new LLMClientFactory() {
                            @Override
                            public io.github.llm4j.LLMClient createClient(String model) {
                                throw new IllegalStateException(
                                        "checking creates no model clients");
                            }

                            @Override
                            public String problem(String model) {
                                return null;
                            }
                        });
        executor.setBaseDir(script.toAbsolutePath().getParent());
        executor.setEnvLookup(TestRunner::standIn);
        executor.setHumanInterface(question -> "");
        executor.setPromptCatalog(PromptSupport.catalog(loaded, script, null));
        List<String> errors = new ArrayList<>();
        // a tool whose option is read from the environment gets a stand-in under test, and a
        // stand-in is not an address or a number: what that breaks is not a problem of the script
        java.util.Set<String> readsEnv = new java.util.HashSet<>();
        for (var tool : loaded.getTools())
            for (var option : tool.getOptions().values())
                if (option.fromEnv()) readsEnv.add("tool " + tool.getName());
        for (var p : new ScriptValidator().validate(loaded, executor.validationContext()))
            if (p.severity() == ScriptValidator.Severity.ERROR
                    && !p.message().contains("not-set-yet")
                    && !(p.construct() != null
                            && readsEnv.contains(p.construct())
                            && (p.message().endsWith(": test")
                                    || p.message().contains("test.invalid"))))
                errors.add(p.toString());
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    script.getFileName()
                            + " does not pass weave check: "
                            + String.join("; ", errors));
    }

    /**
     * What an {@code env.NAME} reads under test: nothing real. Models and tools are scripted, so
     * the value is never used, but a script checks that a URL is one, so a name that looks like an
     * address gets an address that cannot be reached.
     */
    static String standIn(String name) {
        String n = name == null ? "" : name.toUpperCase(java.util.Locale.ROOT);
        return n.contains("URL")
                        || n.contains("ENDPOINT")
                        || n.contains("HOOK")
                        || n.contains("HOST")
                ? "https://test.invalid/" + n.toLowerCase(java.util.Locale.ROOT)
                : "test";
    }

    /** The test files at a path: the file itself, or every {@code *.test.yaml} in the folder. */
    static List<Path> testFiles(Path p) throws IOException {
        if (Files.isRegularFile(p)) return List.of(p);
        if (!Files.isDirectory(p)) return List.of();
        List<Path> out = new ArrayList<>();
        // cases taken from real runs (weave capture) live in a folder of their own under tests/
        for (Path dir : List.of(p, p.resolve("captured"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().endsWith(".test.yaml"))
                        .sorted(Comparator.naturalOrder())
                        .forEach(out::add);
            }
        }
        return out;
    }

    /** How a run is selected and carried out. */
    public record Options(
            String glob,
            boolean lenient,
            boolean failFast,
            List<String> tags,
            List<String> excludeTags,
            int parallel,
            boolean shuffle,
            Long seed) {

        public Options {
            tags = tags == null ? List.of() : List.copyOf(tags);
            excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
            parallel = Math.max(1, parallel);
        }

        public static Options defaults() {
            return new Options(null, false, false, List.of(), List.of(), 1, false, null);
        }
    }

    /** Runs the cases whose name matches {@code glob} (all when null), one after another. */
    public static List<CaseResult> run(
            Suite suite, String glob, boolean lenient, boolean failFast) {
        return run(
                suite, new Options(glob, lenient, failFast, List.of(), List.of(), 1, false, null));
    }

    /** The cases the options select, in the order they would run. */
    public static List<TestFile.Case> select(Suite suite, Options o) {
        Pattern pattern = o.glob() == null ? null : Pattern.compile(globToRegex(o.glob()));
        List<TestFile.Case> out = new ArrayList<>();
        for (TestFile f : suite.files())
            for (TestFile.Case c : f.cases()) {
                if (pattern != null && !pattern.matcher(c.name()).find()) continue;
                List<String> tags = c.tags();
                if (!o.tags().isEmpty() && tags.stream().noneMatch(o.tags()::contains)) continue;
                if (tags.stream().anyMatch(o.excludeTags()::contains)) continue;
                out.add(c);
            }
        if (o.shuffle()) java.util.Collections.shuffle(out, new java.util.Random(seedOf(o)));
        return out;
    }

    /** The seed a shuffled run uses: the one given, else one derived from the clock. */
    public static long seedOf(Options o) {
        return o.seed() != null ? o.seed() : (System.nanoTime() & 0xFFFFFFL);
    }

    /**
     * Runs the selected cases. Cases are independent (each has its own executor, journal, clock and
     * collaborators), so with {@code parallel} above 1 they run side by side; the results come back
     * in the order the cases were selected, whichever finished first.
     */
    public static List<CaseResult> run(Suite suite, Options o) {
        List<TestFile.Case> selected = select(suite, o);
        if (o.parallel() <= 1 || selected.size() <= 1) {
            List<CaseResult> results = new ArrayList<>();
            for (TestFile.Case c : selected) {
                CaseResult r = runCase(suite, c, o.lenient());
                results.add(r);
                if (o.failFast() && !r.passed()) break;
            }
            return results;
        }
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(o.parallel(), selected.size()));
        try {
            java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean();
            List<java.util.concurrent.Future<CaseResult>> futures = new ArrayList<>();
            for (TestFile.Case c : selected)
                futures.add(
                        pool.submit(
                                () -> {
                                    if (stop.get()) return null;
                                    CaseResult r = runCase(suite, c, o.lenient());
                                    if (o.failFast() && !r.passed()) stop.set(true);
                                    return r;
                                }));
            List<CaseResult> results = new ArrayList<>();
            for (var f : futures) {
                try {
                    CaseResult r = f.get();
                    if (r != null) results.add(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs one case of a loaded suite. */
    public static CaseResult runOne(Suite suite, TestFile.Case c) {
        return runCase(suite, c, false);
    }

    private static CaseResult runCase(Suite suite, TestFile.Case c, boolean lenient) {
        TestFile.Case use = lenient ? withLenient(c) : c;
        String workflow = workflowOf(suite, c);
        CaseRunner.Outcome o = CaseRunner.run(suite.script(), workflow, use);
        List<String> failures = new ArrayList<>();
        for (Assertions.Failure x : Assertions.evaluate(use, o, suite.graph(), workflow))
            failures.add(x.message());
        return new CaseResult(
                c.file(),
                c.name(),
                failures.isEmpty(),
                o.millis(),
                failures,
                o.recorder().lenientNotes(),
                c.tags(),
                List.copyOf(o.recorder().steps()));
    }

    /** The coverage of the script by the cases that ran. */
    public static Coverage coverage(Suite suite, List<CaseResult> results) {
        Coverage coverage = new Coverage(suite.graph());
        for (CaseResult r : results) coverage.add(r.steps());
        return coverage;
    }

    static String workflowOf(Suite suite, TestFile.Case c) {
        if (c.has("workflow")) return c.get("workflow").asText();
        for (TestFile f : suite.files())
            if (f.path().toString().equals(c.file()) && f.workflow() != null) return f.workflow();
        List<WorkflowDef> all = suite.loaded().getWorkflows();
        return all.get(0).getName();
    }

    private static TestFile.Case withLenient(TestFile.Case c) {
        var body = c.body().deepCopy();
        body.put("lenient", true);
        return new TestFile.Case(c.file(), c.name(), body);
    }

    private static String globToRegex(String glob) {
        StringBuilder b = new StringBuilder("^");
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> b.append(".*");
                case '?' -> b.append('.');
                default -> b.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return b.append('$').toString();
    }
}
