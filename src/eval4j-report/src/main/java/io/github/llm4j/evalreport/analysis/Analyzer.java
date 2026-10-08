package io.github.llm4j.evalreport.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.evalreport.analysis.Classify.Resolved;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.config.ReportConfig.DimensionConfig;
import io.github.llm4j.evalreport.format.RunBundle;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.MetricDef;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.format.model.ScenarioDef;
import io.github.llm4j.evalreport.format.model.TestOutcome;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.BreakdownCell;
import io.github.llm4j.evalreport.model.ReportModel.BreakdownColumn;
import io.github.llm4j.evalreport.model.ReportModel.BreakdownRow;
import io.github.llm4j.evalreport.model.ReportModel.BreakdownView;
import io.github.llm4j.evalreport.model.ReportModel.CaseView;
import io.github.llm4j.evalreport.model.ReportModel.Coverage;
import io.github.llm4j.evalreport.model.ReportModel.DimensionView;
import io.github.llm4j.evalreport.model.ReportModel.Evidence;
import io.github.llm4j.evalreport.model.ReportModel.FacetView;
import io.github.llm4j.evalreport.model.ReportModel.FamilyView;
import io.github.llm4j.evalreport.model.ReportModel.Meta;
import io.github.llm4j.evalreport.model.ReportModel.MetricView;
import io.github.llm4j.evalreport.model.ReportModel.Note;
import io.github.llm4j.evalreport.model.ReportModel.Rollup;
import io.github.llm4j.evalreport.model.ReportModel.TestRow;
import io.github.llm4j.evalreport.model.ReportModel.WeightedRate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Turns one run bundle into the analysed model (spec 03 §5). Pure given its inputs. */
public final class Analyzer {

    private Analyzer() {}

    public static ReportModel analyze(RunBundle bundle, ReportConfig config, String generatedAt) {
        RunMeta run = bundle.run();
        Classify classify = new Classify(run.metrics());
        List<Resolved> all = new ArrayList<>();
        for (Ev e : bundle.evaluations()) {
            all.add(classify.resolve(e));
        }
        List<Note> notes = new ArrayList<>();
        for (String w : bundle.warnings()) {
            notes.add(new Note("warn", w.startsWith("skipped") ? "BAD_LINE" : "SCORE_CLAMPED", w));
        }
        if ("RUNNING".equals(run.status())) {
            notes.add(
                    new Note(
                            "warn",
                            "RUN_NOT_FINISHED",
                            "This run did not finish; results may be incomplete."));
        }

        Rollup overall = Rollups.of(evs(all));

        // dimensions: union of declared, evaluated and configured
        Set<String> dimIds = new TreeSet<>();
        for (Resolved r : all) {
            dimIds.add(r.dimension());
        }
        for (ScenarioDef s : bundle.scenarios()) {
            if (s.dimensions() != null) {
                dimIds.addAll(s.dimensions());
            }
        }
        dimIds.addAll(config.configuredDimensions().keySet());

        List<DimensionView> dimensions = new ArrayList<>();
        for (String id : dimIds) {
            dimensions.add(dimension(id, all, bundle, config, classify));
        }
        // stable order: dimensions with results first by name, empty ones last
        dimensions.sort(
                (a, b) -> {
                    boolean ea = a.rollup().rate() == null;
                    boolean eb = b.rollup().rate() == null;
                    if (ea != eb) {
                        return ea ? 1 : -1;
                    }
                    return a.name().compareToIgnoreCase(b.name());
                });

        Map<String, List<Resolved>> byFamily = new LinkedHashMap<>();
        for (Resolved r : all) {
            byFamily.computeIfAbsent(r.family(), k -> new ArrayList<>()).add(r);
        }
        List<FamilyView> families = new ArrayList<>();
        for (Map.Entry<String, List<Resolved>> en : byFamily.entrySet()) {
            families.add(family(en.getKey(), en.getValue()));
        }

        WeightedRate weighted = weighted(dimensions);
        List<CaseView> cases = cases(bundle, all, classify);
        List<TestRow> tests = new ArrayList<>();
        for (TestOutcome t : bundle.tests()) {
            tests.add(new TestRow(t.testId(), t.name(), t.status(), t.durationMs(), t.message()));
        }
        if (bundle.scenarios().isEmpty()) {
            notes.add(
                    new Note(
                            "info",
                            "NO_SCENARIOS",
                            "This run declares no golden dataset, so coverage of dimensions is unknown."));
        }

        Meta meta =
                new Meta(
                        config.projectName != null ? config.projectName : run.project(),
                        generatedAt,
                        run.runId(),
                        run.status(),
                        run.runNumber(),
                        run.startedAt(),
                        run.endedAt(),
                        run.branch(),
                        run.commit(),
                        run.profile(),
                        run.env(),
                        run.summary(),
                        config.noiseBand,
                        config.warnGap);
        return new ReportModel(
                meta,
                overall,
                weighted,
                families,
                dimensions,
                cases,
                evidence(run, all),
                notes,
                null,
                List.of(),
                bundle.traces(),
                bundle.optimizations(),
                tests,
                reliability(run, all),
                ReportConfig.presets(dimensionIds(dimensions)),
                new ReportModel.Branding(
                        config.branding.title(),
                        config.branding.logoDataUri(),
                        config.branding.accent()),
                metricNames(run),
                breakdowns(bundle, all, dimensions, config));
    }

    /**
     * One breakdown per configured scenario tag key: rows are the tag's values (for example each
     * agent), columns the dimensions that have results for it. Evaluations whose scenario lacks the
     * tag are left out.
     */
    private static List<BreakdownView> breakdowns(
            RunBundle bundle,
            List<Resolved> all,
            List<DimensionView> dimensions,
            ReportConfig config) {
        Map<String, ScenarioDef> scenarios = new HashMap<>();
        for (ScenarioDef s : bundle.scenarios()) {
            if (s.caseId() != null) {
                scenarios.put(s.caseId(), s);
            }
        }
        Map<String, DimensionView> dimById = new LinkedHashMap<>();
        for (DimensionView d : dimensions) {
            dimById.put(d.id(), d);
        }
        List<BreakdownView> out = new ArrayList<>();
        for (String key : config.breakdownKeys) {
            Map<String, List<Resolved>> byValue = new LinkedHashMap<>();
            for (Resolved r : all) {
                if (!r.ev().counted() || r.ev().caseId() == null) {
                    continue;
                }
                ScenarioDef sc = scenarios.get(r.ev().caseId());
                String value = sc == null ? null : tagValue(sc, key);
                if (value != null) {
                    byValue.computeIfAbsent(value, k -> new ArrayList<>()).add(r);
                }
            }
            if (byValue.isEmpty()) {
                continue;
            }
            java.util.Set<String> used = new java.util.LinkedHashSet<>();
            for (DimensionView d : dimensions) {
                for (List<Resolved> rs : byValue.values()) {
                    if (rs.stream().anyMatch(r -> r.dimension().equals(d.id()))) {
                        used.add(d.id());
                        break;
                    }
                }
            }
            List<BreakdownColumn> columns = new ArrayList<>();
            for (String id : used) {
                DimensionView d = dimById.get(id);
                columns.add(new BreakdownColumn(id, d.name(), d.goal()));
            }
            List<BreakdownRow> rows = new ArrayList<>();
            for (Map.Entry<String, List<Resolved>> en : byValue.entrySet()) {
                List<BreakdownCell> cells = new ArrayList<>();
                for (BreakdownColumn c : columns) {
                    int passed = 0;
                    int failed = 0;
                    java.util.Set<String> failedCases = new java.util.LinkedHashSet<>();
                    for (Resolved r : en.getValue()) {
                        if (!r.dimension().equals(c.dimension())) {
                            continue;
                        }
                        if (r.ev().passed()) {
                            passed++;
                        } else {
                            failed++;
                            ScenarioDef sc = scenarios.get(r.ev().caseId());
                            failedCases.add(
                                    sc == null || sc.id() == null ? r.ev().caseId() : sc.id());
                        }
                    }
                    int n = passed + failed;
                    Double rate =
                            n == 0
                                    ? null
                                    : 100.0 * passed
                                            / n; // percent, like every other rate in the model
                    String status = rate == null ? "NONE" : rate >= c.goal() ? "MET" : "BELOW";
                    cells.add(
                            new BreakdownCell(
                                    c.dimension(),
                                    passed,
                                    failed,
                                    rate,
                                    status,
                                    List.copyOf(failedCases)));
                }
                java.util.Set<String> caseIds = new java.util.LinkedHashSet<>();
                for (Resolved r : en.getValue()) {
                    caseIds.add(r.ev().caseId());
                }
                rows.add(
                        new BreakdownRow(
                                en.getKey(),
                                Rollups.of(evs(en.getValue())),
                                cells,
                                List.copyOf(caseIds)));
            }
            out.add(new BreakdownView(key, "By " + key, columns, rows));
        }
        return out;
    }

    /** The value of a {@code key:value} tag on a scenario, or null. */
    private static String tagValue(ScenarioDef s, String key) {
        if (s.tags() == null) {
            return null;
        }
        for (String t : s.tags()) {
            if (t.startsWith(key + ":")) {
                return t.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static Map<String, String> metricNames(RunMeta run) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MetricDef m : run.metrics()) {
            out.put(m.id(), m.name());
        }
        return out;
    }

    private static List<String> dimensionIds(List<DimensionView> dims) {
        List<String> ids = new ArrayList<>();
        for (DimensionView d : dims) {
            ids.add(d.id());
        }
        return ids;
    }

    /**
     * The first run of letters of a model name: gemini-2.5-pro gives gemini, llama3.3:70b gives
     * llama.
     */
    static String family(String model) {
        if (model == null) {
            return "";
        }
        String m = model.toLowerCase(java.util.Locale.ROOT);
        int i = 0;
        while (i < m.length() && Character.isLetter(m.charAt(i))) {
            i++;
        }
        return m.substring(0, i);
    }

    /**
     * Judge reliability from this run's data: how often repeated samples agree (within 0.1), how
     * often calls failed, and a heuristic warning when a judge and an agent share a model family.
     */
    static List<ReportModel.JudgeReliability> reliability(RunMeta run, List<Resolved> all) {
        List<ReportModel.JudgeReliability> out = new ArrayList<>();
        for (JsonNode j : run.env().path("judges")) {
            String id = j.path("id").asText();
            int judged = 0;
            int multi = 0;
            int agree = 0;
            double spread = 0;
            for (Resolved r : all) {
                Ev e = r.ev();
                if (!e.judged() || !id.equals(e.judgeId())) {
                    continue;
                }
                judged++;
                if (e.samples() != null && e.samples().size() >= 2) {
                    multi++;
                    double lo = 1;
                    double hi = 0;
                    for (double v : e.samples()) {
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                    spread += hi - lo;
                    if (hi - lo <= 0.1) {
                        agree++;
                    }
                }
            }
            int calls = j.path("stats").path("calls").asInt();
            int failures = j.path("stats").path("failures").asInt();
            List<String> same = new ArrayList<>();
            String jf = family(j.path("model").asText(null));
            for (JsonNode a : run.env().path("agents")) {
                if (!jf.isEmpty() && jf.equals(family(a.path("model").asText(null)))) {
                    same.add(a.path("id").asText());
                }
            }
            Double consistency = null;
            if (multi > 0) {
                consistency = (double) agree / multi;
            } else if (j.path("stats").path("selfConsistency").isNumber()) {
                consistency = j.path("stats").path("selfConsistency").asDouble();
            }
            out.add(
                    new ReportModel.JudgeReliability(
                            id,
                            j.path("model").asText(null),
                            judged,
                            multi,
                            consistency,
                            multi == 0 ? null : spread / multi,
                            calls,
                            failures,
                            calls + failures == 0 ? null : (double) failures / (calls + failures),
                            same));
        }
        return out;
    }

    private static List<Ev> evs(List<Resolved> rs) {
        List<Ev> out = new ArrayList<>(rs.size());
        for (Resolved r : rs) {
            out.add(r.ev());
        }
        return out;
    }

    private static FamilyView family(String id, List<Resolved> rs) {
        Map<String, List<Resolved>> byFacet = new LinkedHashMap<>();
        Set<String> dims = new LinkedHashSet<>();
        for (Resolved r : rs) {
            byFacet.computeIfAbsent(r.facet(), k -> new ArrayList<>()).add(r);
            dims.add(r.dimension());
        }
        List<FacetView> facets = new ArrayList<>();
        for (Map.Entry<String, List<Resolved>> en : byFacet.entrySet()) {
            Set<String> fd = new LinkedHashSet<>();
            for (Resolved r : en.getValue()) {
                fd.add(r.dimension());
            }
            facets.add(
                    new FacetView(
                            en.getKey(),
                            ReportConfig.facetName(en.getKey()),
                            Rollups.of(evs(en.getValue())),
                            new ArrayList<>(fd)));
        }
        return new FamilyView(
                id,
                ReportConfig.familyName(id),
                Rollups.of(evs(rs)),
                facets,
                new ArrayList<>(dims));
    }

    private static DimensionView dimension(
            String id,
            List<Resolved> all,
            RunBundle bundle,
            ReportConfig config,
            Classify classify) {
        List<Resolved> mine = new ArrayList<>();
        for (Resolved r : all) {
            if (r.dimension().equals(id)) {
                mine.add(r);
            }
        }
        Rollup rollup = Rollups.of(evs(mine));
        DimensionConfig dc = config.dimension(id);
        Double gap = rollup.rate() == null ? null : rollup.rate() - dc.goal();
        String status;
        if (gap == null) {
            status = "NO_RESULTS";
        } else if (gap >= 0) {
            status = "MEETS_GOAL";
        } else if (gap > -config.warnGap) {
            status = "BELOW_GOAL";
        } else {
            status = "WELL_BELOW_GOAL";
        }
        Map<String, List<Resolved>> byMetric = new LinkedHashMap<>();
        Map<String, List<Resolved>> byFam = new TreeMap<>();
        for (Resolved r : mine) {
            byMetric.computeIfAbsent(r.ev().metric(), k -> new ArrayList<>()).add(r);
            byFam.computeIfAbsent(r.family(), k -> new ArrayList<>()).add(r);
        }
        List<MetricView> metrics = new ArrayList<>();
        for (Map.Entry<String, List<Resolved>> en : byMetric.entrySet()) {
            MetricDef m = classify.metric(en.getKey());
            Resolved first = en.getValue().get(0);
            metrics.add(
                    new MetricView(
                            en.getKey(),
                            m == null ? en.getKey() : m.name(),
                            m == null ? first.ev().kind() : m.kind(),
                            first.family(),
                            first.facet(),
                            m == null ? first.ev().threshold() : m.threshold(),
                            m == null ? null : m.unit(),
                            m == null ? null : m.budget(),
                            m == null ? first.ev().judgeId() : m.judgeId(),
                            Rollups.of(evs(en.getValue()))));
        }
        Map<String, Rollup> famRollups = new LinkedHashMap<>();
        byFam.forEach((k, v) -> famRollups.put(k, Rollups.of(evs(v))));
        return new DimensionView(
                id,
                dc.name(),
                dc.blurb(),
                dc.goal(),
                dc.priority().name(),
                dc.priority().weight,
                rollup,
                gap,
                status,
                coverage(id, mine, bundle, config),
                new ArrayList<>(byFam.keySet()),
                metrics,
                famRollups);
    }

    /** Spec 03 §5.6. */
    private static Coverage coverage(
            String id, List<Resolved> mine, RunBundle bundle, ReportConfig config) {
        List<ScenarioDef> declared = new ArrayList<>();
        for (ScenarioDef s : bundle.scenarios()) {
            if (s.dimensions() != null && s.dimensions().contains(id)) {
                declared.add(s);
            }
        }
        Set<String> evaluatedCases = new LinkedHashSet<>();
        for (Resolved r : mine) {
            if (r.ev().counted() && r.ev().caseId() != null) {
                evaluatedCases.add(r.ev().caseId());
            }
        }
        if (bundle.scenarios().isEmpty()) {
            return new Coverage("NO_DATASET", 0, evaluatedCases.size(), List.of());
        }
        if (declared.isEmpty()) {
            boolean any = !evaluatedCases.isEmpty();
            return new Coverage(
                    any ? "UNDECLARED" : "DECLARED_NO_SCENARIOS",
                    0,
                    evaluatedCases.size(),
                    List.of());
        }
        List<String> missing = new ArrayList<>();
        int done = 0;
        for (ScenarioDef s : declared) {
            String cid = s.caseId();
            if (cid != null && evaluatedCases.contains(cid)) {
                done++;
            } else {
                missing.add(s.name() != null ? s.name() : s.id());
            }
        }
        String state =
                done == 0 ? "NOT_EVALUATED" : missing.isEmpty() ? "COVERED" : "PARTLY_EVALUATED";
        return new Coverage(state, declared.size(), done, missing);
    }

    /** Spec 03 §5.5: priority-weighted pass rate over dimensions that have a result. */
    static WeightedRate weighted(List<DimensionView> dims) {
        double num = 0;
        double goalNum = 0;
        double den = 0;
        int n = 0;
        for (DimensionView d : dims) {
            if (d.rollup().rate() == null || d.weight() <= 0) {
                continue;
            }
            num += d.weight() * d.rollup().rate();
            goalNum += d.weight() * d.goal();
            den += d.weight();
            n++;
        }
        return den == 0
                ? new WeightedRate(null, null, 0)
                : new WeightedRate(num / den, goalNum / den, n);
    }

    private static List<CaseView> cases(RunBundle bundle, List<Resolved> all, Classify classify) {
        Map<String, ScenarioDef> scenarios = new HashMap<>();
        for (ScenarioDef s : bundle.scenarios()) {
            if (s.caseId() != null) {
                scenarios.put(s.caseId(), s);
            }
        }
        Map<String, TestOutcome> tests = new HashMap<>();
        for (TestOutcome t : bundle.tests()) {
            if (t.caseId() != null) {
                tests.put(t.caseId(), t);
            }
            if (t.testId() != null) {
                tests.putIfAbsent(t.testId(), t);
            }
        }
        Map<String, List<Resolved>> byCase = new LinkedHashMap<>();
        for (Resolved r : all) {
            String cid = r.ev().caseId() != null ? r.ev().caseId() : "unknown";
            byCase.computeIfAbsent(cid, k -> new ArrayList<>()).add(r);
        }
        List<CaseView> out = new ArrayList<>();
        for (Map.Entry<String, List<Resolved>> en : byCase.entrySet()) {
            List<Resolved> rs = en.getValue();
            ScenarioDef sc = scenarios.get(en.getKey());
            Ev first = rs.get(0).ev();
            TestOutcome t =
                    tests.get(en.getKey()) != null
                            ? tests.get(en.getKey())
                            : tests.get(first.testId());
            boolean anyFail = false;
            boolean anyPass = false;
            Map<String, String> dims = new LinkedHashMap<>();
            Set<String> dimSet = new LinkedHashSet<>();
            List<Ev> evs = new ArrayList<>();
            for (Resolved r : rs) {
                evs.add(r.ev());
                dimSet.add(r.dimension());
                if (r.ev().counted()) {
                    if (r.ev().passed()) {
                        anyPass = true;
                    } else {
                        anyFail = true;
                    }
                }
                dims.put(r.ev().key(), r.dimension());
            }
            String name =
                    sc != null && sc.name() != null
                            ? sc.name()
                            : sc != null && sc.id() != null
                                    ? sc.id()
                                    : t != null && t.name() != null
                                            ? t.name()
                                            : first.testId() != null ? first.testId() : en.getKey();
            String outcome = anyFail ? "FAILED" : anyPass ? "PASSED" : "NOT_EVALUATED";
            String input = sc != null && sc.input() != null ? sc.input() : first.input();
            if (input == null) {
                for (Ev e : evs) {
                    if (e.input() != null) {
                        input = e.input();
                        break;
                    }
                }
            }
            out.add(
                    new CaseView(
                            en.getKey(),
                            sc != null ? sc.id() : first.scenarioId(),
                            name,
                            input,
                            new ArrayList<>(dimSet),
                            rs.get(0).family(),
                            outcome,
                            evs,
                            dims));
        }
        out.sort(
                (a, b) -> {
                    int oa =
                            a.outcome().equals("FAILED")
                                    ? 0
                                    : a.outcome().equals("NOT_EVALUATED") ? 1 : 2;
                    int ob =
                            b.outcome().equals("FAILED")
                                    ? 0
                                    : b.outcome().equals("NOT_EVALUATED") ? 1 : 2;
                    return oa != ob
                            ? Integer.compare(oa, ob)
                            : a.name().compareToIgnoreCase(b.name());
                });
        return out;
    }

    /** Spec 03 §5.7. */
    private static Evidence evidence(RunMeta run, List<Resolved> all) {
        int fresh = 0;
        int reused = 0;
        int carried = 0;
        double cost = 0;
        boolean anyCost = false;
        double freshJudgedCost = 0;
        int freshJudged = 0;
        boolean allFreshHaveCost = true;
        int judgedTotal = 0;
        for (Resolved r : all) {
            Ev e = r.ev();
            if (!e.counted()) {
                continue;
            }
            switch (e.sourceOrFresh()) {
                case "REUSED" -> reused++;
                case "CARRIED" -> carried++;
                default -> fresh++;
            }
            if (e.costUsd() != null) {
                cost += e.costUsd();
                anyCost = true;
            }
            if (e.judged()) {
                judgedTotal++;
                if ("FRESH".equals(e.sourceOrFresh())) {
                    freshJudged++;
                    if (e.costUsd() == null) {
                        allFreshHaveCost = false;
                    } else {
                        freshJudgedCost += e.costUsd();
                    }
                }
            }
        }
        Double estimated = null;
        Double saved = null;
        if (freshJudged > 0 && allFreshHaveCost) {
            estimated = freshJudgedCost / freshJudged * judgedTotal;
            saved = Math.max(0, estimated - cost);
        }
        JsonNode budget = run.profile().path("judgeBudgetUsd");
        return new Evidence(
                fresh,
                reused,
                carried,
                anyCost ? cost : null,
                budget.isNumber() ? budget.asDouble() : null,
                estimated,
                saved,
                run.profile().path("name").asText(null));
    }
}
