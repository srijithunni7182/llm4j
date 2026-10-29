package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.EvalStatus;
import io.github.llm4j.eval.export.Evaluation;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.MetricRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes an evaluation as the eval4j run bundle the dashboard reads: every case with its input, the
 * output that was judged and each line's verdict with its reason, and, for every agent that took
 * part, a case of its own (tagged {@code agent:<Name>}) that records whether its answers were whole
 * and whether it needed a fallback. That is what fills the dashboard's By-agent table without the
 * person tagging anything.
 */
public final class EvalBundleWriter {
    private static final Pattern SCORE = Pattern.compile("^score (\\d(?:\\.\\d+)?): ?(.*)$", Pattern.DOTALL);
    private static final String ANSWER_WHOLE = "answer-whole";
    private static final String NO_FALLBACK = "step-used-no-fallback";

    private EvalBundleWriter() {}

    /**
     * Writes the bundle under {@code exportRoot/runs/<runId>}.
     *
     * @return the bundle's folder
     */
    public static Path write(EvalReport.Run run, Path exportRoot, String runId) {
        String oldDir = System.getProperty(ExportConfig.DIR);
        String oldId = System.getProperty(ExportConfig.RUN_ID);
        System.setProperty(ExportConfig.DIR, exportRoot.toString());
        System.setProperty(ExportConfig.RUN_ID, runId);
        try {
            EvalRun bundle = EvalRun.get();
            declare(bundle, run);
            for (ScenarioResult r : run.results()) {
                recordCase(bundle, r);
                for (AgentFacts a : r.agents()) recordStep(bundle, r, a);
            }
            bundle.finish();
            return exportRoot.resolve("runs").resolve(runId);
        } finally {
            restore(ExportConfig.DIR, oldDir);
            restore(ExportConfig.RUN_ID, oldId);
        }
    }

    private static void restore(String key, String old) {
        if (old == null) System.clearProperty(key);
        else System.setProperty(key, old);
    }

    private static void declare(EvalRun bundle, EvalReport.Run run) {
        for (String agent : EvalSections.agentNames(run))
            bundle.declareAgent(agent, null, null, null, null, List.of());
        if (run.judge() != null)
            bundle.declareJudge(run.judge().model(), null, run.judge().model(), null, 1, "single sample");
        // a case that ran several times is declared once; its runs are separate evaluations of it
        java.util.Map<String, EvalScenario> scenarios = new java.util.LinkedHashMap<>();
        for (ScenarioResult r : run.results()) {
            scenarios.putIfAbsent(r.label(), scenario(r, r.label(), taggedWithAgent(r)));
            for (AgentFacts a : r.agents())
                if (!isAimedAt(r, a))
                    scenarios.putIfAbsent(r.label() + "/" + a.agent(), stepScenario(r, a));
        }
        bundle.declareDataset(
                "golden", run.script(), run.settings().get("dataset"), new ArrayList<>(scenarios.values()));
    }

    private static boolean isAimedAt(ScenarioResult r, AgentFacts a) {
        return r.kind().equals("agent") && r.target().equals(a.agent());
    }

    private static List<String> taggedWithAgent(ScenarioResult r) {
        List<String> tags = new ArrayList<>(r.tags());
        if (r.kind().equals("agent") && tags.stream().noneMatch(t -> t.startsWith("agent:")))
            tags.add("agent:" + r.target());
        return tags;
    }

    private static EvalScenario scenario(ScenarioResult r, String id, List<String> tags) {
        List<String> rubric = new ArrayList<>();
        List<String> expect = new ArrayList<>();
        for (ScenarioResult.Check c : r.checks()) {
            if (c.kind().equals("rubric")) rubric.add(c.text());
            else if (c.kind().equals("expect")) expect.add(c.text());
        }
        return new EvalScenario(
                r.name() == null ? id : r.name(), r.input(), null, null, null, null, null, id,
                r.dimensions().isEmpty() ? null : r.dimensions(), tags,
                rubric.isEmpty() ? null : rubric, expect.isEmpty() ? null : expect);
    }

    /** The case that stands for one agent's part in a workflow case. */
    private static EvalScenario stepScenario(ScenarioResult r, AgentFacts a) {
        return new EvalScenario(
                r.label() + " / " + a.agent(), r.input(), null, null, null, null, null,
                r.label() + "/" + a.agent(), List.of("reliability"), List.of("agent:" + a.agent(), "step"),
                null, null);
    }

    private static void recordCase(EvalRun bundle, ScenarioResult r) {
        EvalScenario sc = scenario(r, r.label(), taggedWithAgent(r));
        bundle.bindTest("weave eval " + r.file(), r.label());
        bundle.bindScenario(sc);
        try {
            String dimension = r.dimensions().isEmpty() ? null : r.dimensions().get(0);
            if (r.error() != null)
                bundle.record(
                        Evaluation.builder(metric("run", "The run completed", dimension))
                                .status(EvalStatus.ERROR)
                                .reason(r.error())
                                .details(r.input(), r.output(), null, null));
            for (ScenarioResult.Check c : r.checks()) {
                String name = c.text().isEmpty() ? c.kind() : c.text();
                Evaluation.Builder e =
                        Evaluation.builder(metric(name, name, c.dimension() != null ? c.dimension() : dimension))
                                .details(r.input(), r.output(), null, null);
                switch (c.status()) {
                    case UNJUDGED -> e.status(EvalStatus.NOT_EVALUATED).reason(c.detail());
                    case PASS, FAIL -> {
                        boolean pass = c.status() == Status.PASS;
                        Matcher m = c.detail() == null ? null : SCORE.matcher(c.detail());
                        if (m != null && m.matches()) {
                            e.score(Double.parseDouble(m.group(1))).reason(m.group(2));
                        } else {
                            e.score(pass ? 1.0 : 0.0).reason(c.detail());
                        }
                        e.passed(pass);
                    }
                }
                bundle.record(e);
            }
            // the agent aimed at by this case: its own answers and fallbacks, beside its quality
            for (AgentFacts a : r.agents())
                if (isAimedAt(r, a)) stepChecks(bundle, a, r);
        } finally {
            bundle.unbind();
        }
    }

    private static void recordStep(EvalRun bundle, ScenarioResult r, AgentFacts a) {
        if (isAimedAt(r, a)) return;
        EvalScenario sc = stepScenario(r, a);
        bundle.bindTest("weave eval " + r.file(), r.label() + " / " + a.agent());
        bundle.bindScenario(sc);
        try {
            stepChecks(bundle, a, r);
        } finally {
            bundle.unbind();
        }
    }

    private static void stepChecks(EvalRun bundle, AgentFacts a, ScenarioResult r) {
        bundle.record(
                Evaluation.builder(MetricRef.assertion(ANSWER_WHOLE, "Answers were whole", "agents", "answers", "completeness"))
                        .score(a.cutAnswers() == 0 ? 1.0 : 0.0)
                        .passed(a.cutAnswers() == 0)
                        .reason(a.cutAnswers() == 0 ? "no answer was cut at the output limit" : a.cutAnswers() + " answer(s) were cut at the output limit")
                        .usage((int) a.calls(), (int) a.promptTokens(), (int) a.completionTokens(), a.cost() == null ? null : a.cost().doubleValue())
                        .details(r.input(), null, null, null));
        bundle.record(
                Evaluation.builder(MetricRef.assertion(NO_FALLBACK, "No step needed a fallback", "agents", "answers", "reliability"))
                        .score(a.fallbacks() == 0 ? 1.0 : 0.0)
                        .passed(a.fallbacks() == 0)
                        .reason(a.fallbacks() == 0 ? "every step of this agent answered" : a.fallbacks() + " step(s) took their on_failure fallback")
                        .details(r.input(), null, null, null));
    }

    private static MetricRef metric(String id, String name, String dimension) {
        MetricRef base = MetricRef.of(name);
        return new MetricRef(
                MetricRef.slug(id.toLowerCase(Locale.ROOT)), name, base.kind(), base.family(), base.facet(),
                dimension != null ? dimension : base.dimension(), base.threshold(), base.unit(), base.budget());
    }
}
