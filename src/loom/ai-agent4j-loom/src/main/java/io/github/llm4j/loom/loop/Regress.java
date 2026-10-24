package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.whatif.WhatIf;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs a candidate script over a sample of real history and says what changes. Each sampled run is
 * replayed from its journal with the candidate (observe-replay, no model, effects only described),
 * so a change to the control flow costs nothing. A run whose model input the candidate changes is
 * counted as needing a model and never guessed. The store is only read.
 */
public final class Regress {

    private Regress() {}

    public enum Verdict {
        SAME,
        CHANGED_ROUTE,
        CHANGED_RESULT,
        NEEDS_MODEL,
        DID_NOT_FINISH,
        /** Not compared: the spend cap was reached before this run. */
        NOT_COMPARED
    }

    /** Raised by a judge or a live comparison that finds the cap already spent. */
    public static final class CapReached extends RuntimeException {
        public CapReached() {
            super("the spend cap is reached");
        }
    }

    /** Live models for the runs that need one, under a cap shared by the whole regress. */
    public record Live(
            io.github.llm4j.loom.execution.LLMClientFactory models,
            io.github.llm4j.budget.PriceTable prices,
            java.util.function.Function<String, String> env,
            Spent spent) {}

    /**
     * What a workflow costs per run, as an estimate from the script (tokens, and money if priced).
     */
    public record CostFigure(long typicalTokens, java.math.BigDecimal typicalCost) {}

    /** The review's cost model, as a function of a script: workflow name to figure. */
    @FunctionalInterface
    public interface Costing {
        Map<String, CostFigure> of(Path script) throws Exception;
    }

    /** An approved captured case that the candidate no longer passes. */
    public record ApprovedFlip(String file, String name, List<String> failures) {}

    /**
     * @param prompts agent to prompt text or {@code @version}; @param models agent to model id: the
     *     changes to try without editing files
     */
    public record Request(
            Path candidate,
            Baselines baselines,
            Path store,
            Instant since,
            int limit,
            Sampler.Strategy strategy,
            long seed,
            String judge,
            Map<String, String> prompts,
            Map<String, String> models,
            long perRunSeconds,
            Live live,
            Judges.Judge textJudge,
            Costing costing,
            List<Path> approvedCases) {
        public Request {
            prompts = prompts == null ? Map.of() : prompts;
            models = models == null ? Map.of() : models;
            approvedCases = approvedCases == null ? List.of() : approvedCases;
        }

        /** Recorded answers only, nothing judged, no cost figures. */
        public Request(
                Path candidate,
                Baselines baselines,
                Path store,
                Instant since,
                int limit,
                Sampler.Strategy strategy,
                long seed,
                String judge,
                Map<String, String> prompts,
                Map<String, String> models,
                long perRunSeconds) {
            this(
                    candidate,
                    baselines,
                    store,
                    since,
                    limit,
                    strategy,
                    seed,
                    judge,
                    prompts,
                    models,
                    perRunSeconds,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** One compared run. */
    public record Comparison(
            String run,
            String stratum,
            Verdict verdict,
            List<String> unsafe,
            String firstDifference,
            String candidateTakes,
            String detail,
            String before,
            String after,
            boolean live,
            long tokens,
            java.math.BigDecimal cost) {
        public Comparison(
                String run,
                String stratum,
                Verdict verdict,
                List<String> unsafe,
                String firstDifference,
                String candidateTakes,
                String detail,
                String before,
                String after) {
            this(
                    run,
                    stratum,
                    verdict,
                    unsafe,
                    firstDifference,
                    candidateTakes,
                    detail,
                    before,
                    after,
                    false,
                    0,
                    null);
        }

        public Comparison(
                String run,
                String stratum,
                Verdict verdict,
                List<String> unsafe,
                String firstDifference,
                String candidateTakes,
                String detail) {
            this(
                    run,
                    stratum,
                    verdict,
                    unsafe,
                    firstDifference,
                    candidateTakes,
                    detail,
                    null,
                    null);
        }

        public boolean isUnsafe() {
            return !unsafe.isEmpty();
        }
    }

    /** A run that was left out, and why. */
    public record Excluded(String run, String why) {}

    public record Report(
            String candidate,
            String baseline,
            String store,
            int runsInStore,
            Sampler.Sample sample,
            List<Comparison> comparisons,
            List<Excluded> excluded,
            String judge,
            boolean storeUntouched,
            boolean stoppedByCap,
            Spent spent,
            Map<String, CostFigure> costBefore,
            Map<String, CostFigure> costAfter,
            List<ApprovedFlip> approvedFlips,
            int approvedRun) {

        public long liveCompared() {
            return comparisons.stream().filter(Comparison::live).count();
        }

        public long count(Verdict v) {
            return comparisons.stream().filter(c -> c.verdict() == v).count();
        }

        public List<Comparison> unsafe() {
            return comparisons.stream().filter(Comparison::isUnsafe).toList();
        }
    }

    public static Report run(Request r) throws Exception {
        String before = StoreReader.fingerprint(r.store());
        List<RunRecord> all = StoreReader.list(r.store());
        List<Excluded> excluded = new ArrayList<>();
        List<RunRecord> eligible = new ArrayList<>();
        Map<String, Path> baselineOf = new LinkedHashMap<>();
        Set<String> workflows = candidateWorkflows(r.candidate());
        for (RunRecord run : all) {
            if (r.since() != null && run.modified().isBefore(r.since())) continue;
            if (!workflows.contains(run.workflow())) {
                excluded.add(
                        new Excluded(
                                run.id(),
                                "ran workflow "
                                        + run.workflow()
                                        + ", which the candidate does not have"));
                continue;
            }
            java.util.Optional<Path> base = r.baselines().forHash(run.scriptHash());
            if (base.isEmpty()) {
                excluded.add(
                        new Excluded(
                                run.id(),
                                run.scriptHash() == null
                                        ? "recorded no script version"
                                        : "its script version cannot be recovered"));
                continue;
            }
            if (r.baselines().fixed()
                    && run.scriptHash() != null
                    && !run.scriptHash()
                            .equals(io.github.llm4j.loom.travel.ScriptHash.of(base.get()))) {
                excluded.add(new Excluded(run.id(), "ran another version than the baseline"));
                continue;
            }
            eligible.add(run);
            baselineOf.put(run.id(), base.get());
        }
        Sampler.Sample sample =
                Sampler.choose(
                        eligible,
                        r.strategy(),
                        r.limit(),
                        r.seed(),
                        Regress::stratum,
                        run -> Candidates.classify(run).classes().contains("failed"));
        List<Comparison> comparisons = compareAll(r, sample.chosen(), baselineOf);
        boolean untouched = StoreReader.fingerprint(r.store()).equals(before);
        boolean capped = comparisons.stream().anyMatch(c -> c.verdict() == Verdict.NOT_COMPARED);
        Map<String, CostFigure> costBefore = Map.of();
        Map<String, CostFigure> costAfter = Map.of();
        if (r.costing() != null && !baselineOf.isEmpty()) {
            try {
                Path any = baselineOf.values().iterator().next();
                costBefore = r.costing().of(any);
                costAfter = r.costing().of(r.candidate());
            } catch (Exception e) {
                // no estimate: the report says there is none
            }
        }
        List<ApprovedFlip> flips = new ArrayList<>();
        int approvedRun = approved(r, flips);
        return new Report(
                r.candidate().getFileName().toString(),
                r.baselines().describe(),
                r.store().toString(),
                all.size(),
                sample,
                comparisons,
                excluded,
                r.judge(),
                untouched,
                capped,
                r.live() == null ? null : r.live().spent(),
                costBefore,
                costAfter,
                flips,
                approvedRun);
    }

    /**
     * The approved captured cases, run against the candidate whatever the sample was. A flip on one
     * is an approved behaviour broken. Returns how many cases ran.
     */
    private static int approved(Request r, List<ApprovedFlip> flips) throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path p : r.approvedCases()) {
            if (java.nio.file.Files.isDirectory(p)) {
                try (var walk = java.nio.file.Files.walk(p)) {
                    walk.filter(f -> f.toString().endsWith(".test.yaml"))
                            .sorted()
                            .forEach(files::add);
                }
            } else if (java.nio.file.Files.isRegularFile(p)) files.add(p);
        }
        List<Path> approvedFiles = new ArrayList<>();
        for (Path f : files) {
            var tf = io.github.llm4j.loom.wtest.TestFile.read(f);
            if ("approved".equals(tf.capturedStatus())) approvedFiles.add(f);
        }
        if (approvedFiles.isEmpty()) return 0;
        // an approved case is about "the script", wherever its file says it was captured from:
        // point copies of the files at the candidate and run those
        Path tmp = java.nio.file.Files.createTempDirectory("weave-regress-cases-");
        List<Path> pointed = new ArrayList<>();
        try {
            for (Path f : approvedFiles) {
                String text = java.nio.file.Files.readString(f);
                String at = "script: " + r.candidate().toAbsolutePath().normalize();
                String out =
                        text.contains("\nscript:")
                                ? text.replaceFirst(
                                        "(?m)^script:.*$",
                                        java.util.regex.Matcher.quoteReplacement(at))
                                : text + "\n" + at + "\n";
                Path copy = tmp.resolve(f.getFileName());
                java.nio.file.Files.writeString(copy, out);
                pointed.add(copy);
            }
            return runApproved(r, pointed, approvedFiles, flips);
        } finally {
            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            }
        }
    }

    private static int runApproved(
            Request r, List<Path> approvedFiles, List<Path> named, List<ApprovedFlip> flips)
            throws Exception {
        var suite = io.github.llm4j.loom.wtest.TestRunner.load(r.candidate(), approvedFiles);
        if (!suite.problems().isEmpty()) {
            for (Path f : approvedFiles)
                flips.add(
                        new ApprovedFlip(
                                f.getFileName().toString(), "(the file)", suite.problems()));
            return approvedFiles.size();
        }
        var results = io.github.llm4j.loom.wtest.TestRunner.run(suite, null, false, false);
        for (var res : results)
            if (!res.passed())
                flips.add(
                        new ApprovedFlip(
                                java.nio.file.Path.of(res.file()).getFileName().toString(),
                                res.name(),
                                res.failures()));
        return results.size();
    }

    private static Set<String> candidateWorkflows(Path candidate) throws Exception {
        var script = new io.github.llm4j.loom.execution.LoomLoader().load(candidate.toString());
        Set<String> names = new java.util.LinkedHashSet<>();
        script.getWorkflows().forEach(w -> names.add(w.getName()));
        return names;
    }

    /** The classifier labels and human answers a run recorded, and whether it failed. */
    static String stratum(RunRecord run) {
        List<String> parts = new ArrayList<>();
        for (var e : new TreeMap<>(run.journal()).entrySet()) {
            var entry = e.getValue();
            if (entry.kind().equals("classify") && entry.value() instanceof Map<?, ?> m) {
                m.entrySet().stream()
                        .filter(
                                x ->
                                        !String.valueOf(x.getKey())
                                                .matches(
                                                        ".*(_confidence|_probability)|confidence|calibrated|backend|model"))
                        .forEach(x -> parts.add(x.getKey() + "=" + x.getValue()));
            } else if (entry.kind().equals("human")) parts.add("asked a person");
            else if (entry.kind().equals("failed") || e.getKey().endsWith("#failure"))
                parts.add("failed");
        }
        return parts.isEmpty()
                ? "no recorded choices"
                : String.join(", ", parts.stream().sorted().distinct().toList());
    }

    private static List<Comparison> compareAll(
            Request r, List<RunRecord> chosen, Map<String, Path> baselineOf) throws Exception {
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors()),
                        t -> {
                            Thread th = new Thread(t, "regress");
                            th.setDaemon(true);
                            return th;
                        });
        try {
            if (r.live() != null) {
                // live comparisons spend money: one at a time, and none after the cap is reached
                List<Comparison> out = new ArrayList<>();
                for (RunRecord run : chosen) {
                    if (r.live().spent().exhausted()) {
                        out.add(
                                new Comparison(
                                        run.id(),
                                        stratum(run),
                                        Verdict.NOT_COMPARED,
                                        List.of(),
                                        null,
                                        null,
                                        "the spend cap was reached before this run"));
                        continue;
                    }
                    Future<Comparison> f =
                            pool.submit(() -> compare(r, run, baselineOf.get(run.id())));
                    try {
                        out.add(f.get(r.perRunSeconds() * 4, TimeUnit.SECONDS));
                    } catch (TimeoutException e) {
                        f.cancel(true);
                        out.add(
                                new Comparison(
                                        run.id(),
                                        stratum(run),
                                        Verdict.DID_NOT_FINISH,
                                        List.of("the candidate did not finish this run"),
                                        null,
                                        null,
                                        "stopped after " + r.perRunSeconds() * 4 + " s"));
                    }
                }
                return out;
            }
            List<Future<Comparison>> futures = new ArrayList<>();
            for (RunRecord run : chosen) {
                Callable<Comparison> job = () -> compare(r, run, baselineOf.get(run.id()));
                futures.add(pool.submit(job));
            }
            List<Comparison> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                RunRecord run = chosen.get(i);
                try {
                    out.add(futures.get(i).get(r.perRunSeconds(), TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    out.add(
                            new Comparison(
                                    run.id(),
                                    stratum(run),
                                    Verdict.DID_NOT_FINISH,
                                    List.of("the candidate did not finish this run (a loop?)"),
                                    null,
                                    null,
                                    "stopped after " + r.perRunSeconds() + " s"));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static Comparison compare(Request r, RunRecord run, Path baseline) {
        String stratum = stratum(run);
        try {
            String first = run.workflow() + "/s0";
            // the people answered what they answered; the candidate gets those answers in order,
            // and a candidate that asks more than were asked is reported, not answered for
            ObserveReplay.Result recorded =
                    ObserveReplay.run(
                            new ObserveReplay.Options(
                                    baseline, run.workflow(), run.inputs(), Set.of()),
                            run.asJournal());
            List<WhatIf.Change> people = new ArrayList<>();
            for (String step : recorded.steps()) {
                var entry = recorded.journal().get(step);
                if (entry != null && entry.kind().equals("human"))
                    people.add(new WhatIf.Change("human", null, String.valueOf(entry.value()), 1));
            }
            WhatIf.Outcome o =
                    WhatIf.run(
                            new WhatIf.Request(
                                    baseline,
                                    run.workflow(),
                                    run.inputs(),
                                    run.asJournal(),
                                    new Why.Selector(first, null, null),
                                    people,
                                    r.live() == null ? "recorded" : "live",
                                    null,
                                    Set.of(),
                                    r.candidate(),
                                    r.prompts(),
                                    r.models(),
                                    liveOf(r)));
            boolean liveCalls = o.candidate().calls() > 0;
            if (r.live() != null)
                r.live()
                        .spent()
                        .add(o.candidate().tokens(), o.candidate().calls(), o.candidate().cost());
            if (o.candidate().status().equals("budget"))
                return new Comparison(
                        run.id(),
                        stratum,
                        Verdict.NOT_COMPARED,
                        List.of(),
                        null,
                        null,
                        "the spend cap was reached during this run: " + o.candidate().reason(),
                        null,
                        null,
                        true,
                        o.candidate().tokens(),
                        o.candidate().cost());
            List<String> unsafe = new ArrayList<>(unsafeReasons(o));
            Function<String, String> was = labels(baseline).andThen(Regress::shape);
            Function<String, String> now = labels(r.candidate()).andThen(Regress::shape);
            List<String> routeWas = o.original().steps().stream().map(was).toList();
            List<String> routeNow = o.candidate().steps().stream().map(now).toList();
            if (o.need() != null) {
                // the candidate stopped where it needs a model's answer. If everything before that
                // (and the statement itself) is as in the run, only the question changed: the run
                // cannot be compared. If the way there differs, the route changed for certain.
                int n = routeNow.size();
                boolean sameWay = n <= routeWas.size() && routeNow.equals(routeWas.subList(0, n));
                if (sameWay)
                    return new Comparison(
                            run.id(),
                            stratum,
                            Verdict.NEEDS_MODEL,
                            unsafe,
                            "at " + now.apply(o.need().step()),
                            null,
                            o.need().what() + " (" + o.need().because() + ")");
                String took = routeNow.isEmpty() ? null : routeNow.get(n - 1);
                return new Comparison(
                        run.id(),
                        stratum,
                        Verdict.CHANGED_ROUTE,
                        unsafe,
                        firstDifference(routeWas, routeNow),
                        took,
                        "and the candidate then needs a model's answer, so the rest of its path is not known");
            }
            List<String> onlyWas = minus(routeWas, routeNow);
            List<String> onlyNow = minus(routeNow, routeWas);
            unsafe.addAll(humanRemoved(onlyWas, onlyNow));
            boolean routeChanged = !routeWas.equals(routeNow);
            boolean resultChanged;
            try {
                resultChanged = resultChanged(o, r.judge(), r.textJudge());
            } catch (CapReached cap) {
                return new Comparison(
                        run.id(),
                        stratum,
                        Verdict.NOT_COMPARED,
                        unsafe,
                        firstDifference(routeWas, routeNow),
                        null,
                        "the spend cap was reached before this run's answers could be judged");
            }
            Verdict v =
                    routeChanged
                            ? Verdict.CHANGED_ROUTE
                            : resultChanged ? Verdict.CHANGED_RESULT : Verdict.SAME;
            String takes = onlyNow.isEmpty() ? null : onlyNow.get(0);
            String firstDiff = firstDifference(routeWas, routeNow);
            return new Comparison(
                    run.id(),
                    stratum,
                    v,
                    unsafe,
                    firstDiff,
                    takes,
                    "",
                    o.originalResult() == null ? null : String.valueOf(o.originalResult()),
                    o.candidateResult() == null ? null : String.valueOf(o.candidateResult()),
                    liveCalls,
                    o.candidate().tokens(),
                    o.candidate().cost());
        } catch (Exception e) {
            return new Comparison(
                    run.id(),
                    stratum,
                    Verdict.DID_NOT_FINISH,
                    List.of(),
                    null,
                    null,
                    "could not be compared: " + (e.getMessage() == null ? e : e.getMessage()));
        }
    }

    /** A change from fewer effects or a person to a new effect or no person. */
    static List<String> unsafeReasons(WhatIf.Outcome o) {
        List<String> out = new ArrayList<>();
        if (o.effects() != null)
            for (WhatIf.Effect e : o.effects().onlyCandidate())
                out.add("NEW EFFECT: " + e.statement() + " reaches " + e.via());
        return out;
    }

    private static final java.util.Map<Path, Function<String, String>> LABELS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Function<String, String> labels(Path script) {
        return LABELS.computeIfAbsent(
                script.toAbsolutePath().normalize(),
                p -> {
                    try {
                        return Why.labels(p);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    /**
     * A step as it counts for the route: what it does, not the wording of the condition it tests (a
     * reworded condition that sends every run the same way is not a changed route).
     */
    static String shape(String label) {
        if (label.endsWith("?")) return "decision";
        if (label.startsWith("loop until")) return "loop";
        return label;
    }

    /** Steps in {@code a} that {@code b} does not have as many of, in order. */
    static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> left = new java.util.HashMap<>();
        b.forEach(x -> left.merge(x, 1, Integer::sum));
        List<String> out = new ArrayList<>();
        for (String x : a) {
            Integer k = left.get(x);
            if (k != null && k > 0) left.put(x, k - 1);
            else out.add(x);
        }
        return out;
    }

    static String firstDifference(List<String> was, List<String> now) {
        int n = Math.min(was.size(), now.size());
        for (int i = 0; i < n; i++)
            if (!was.get(i).equals(now.get(i)))
                return "the run took \""
                        + was.get(i)
                        + "\" at step "
                        + (i + 1)
                        + ", the candidate takes \""
                        + now.get(i)
                        + "\"";
        if (was.size() != now.size())
            return was.size() > now.size()
                    ? "the candidate ends after "
                            + now.size()
                            + " steps; the run went on to \""
                            + was.get(n)
                            + "\""
                    : "the candidate goes on to \"" + now.get(n) + "\" after the run's last step";
        return null;
    }

    private static List<String> humanRemoved(List<String> onlyWas, List<String> onlyNow) {
        long before = onlyWas.stream().filter(Regress::asksAPerson).count();
        long after = onlyNow.stream().filter(Regress::asksAPerson).count();
        return before > after
                ? List.of("a person is no longer asked (" + (before - after) + " step(s))")
                : List.of();
    }

    /** A step label that is a person being asked (the graph's wording, or the statement's). */
    private static boolean asksAPerson(String label) {
        return label.startsWith("ask a person") || label.startsWith("human_prompt");
    }

    private static boolean resultChanged(WhatIf.Outcome o, String judge, Judges.Judge textJudge)
            throws Exception {
        Object a = o.originalResult();
        Object b = o.candidateResult();
        if (Objects.equals(a, b)) return false;
        boolean typed = a instanceof Map || b instanceof Map || looksTyped(a) || looksTyped(b);
        if (typed) return true;
        if (textJudge != null && a != null && b != null)
            return textJudge.differs(String.valueOf(a), String.valueOf(b));
        return judge.equals("exact") || judge.equals("label");
    }

    /** Live models for one run, under what is left of the shared cap. */
    private static ObserveReplay.Live liveOf(Request r) {
        if (r.live() == null) return null;
        Spent sp = r.live().spent();
        return new ObserveReplay.Live(
                r.live().models(),
                sp.remainingTokens(),
                sp.remainingCost(),
                r.live().prices(),
                r.live().env());
    }

    private static boolean looksTyped(Object o) {
        return o instanceof String s && s.strip().startsWith("{") && s.strip().endsWith("}");
    }
}
