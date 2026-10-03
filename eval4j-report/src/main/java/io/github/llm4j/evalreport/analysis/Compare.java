package io.github.llm4j.evalreport.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.evalreport.analysis.Classify.Resolved;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunBundle;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.ScenarioDef;
import io.github.llm4j.evalreport.model.ReportModel.ChangedCase;
import io.github.llm4j.evalreport.model.ReportModel.CompareModel;
import io.github.llm4j.evalreport.model.ReportModel.DimensionCompare;
import io.github.llm4j.evalreport.model.ReportModel.EnvRow;
import io.github.llm4j.evalreport.model.ReportModel.Note;
import io.github.llm4j.evalreport.model.ReportModel.ScatterPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Run-vs-run comparison (spec 03 §7). A pure function of two bundles and the configuration. */
public final class Compare {

    private static final int MAX_SCATTER = 5000;

    private Compare() {}

    public static CompareModel compare(
            RunBundle base, RunBundle cand, ReportConfig config, String basePicked) {
        Classify baseClass = new Classify(base.run().metrics());
        Classify candClass = new Classify(cand.run().metrics());
        Map<String, Ev> b = byKey(base.evaluations());
        Map<String, Ev> c = byKey(cand.evaluations());
        Map<String, String> caseNames = caseNames(cand, base);

        int matched = 0;
        int worse = 0;
        int better = 0;
        int same = 0;
        int withinNoise = 0;
        int added = 0;
        int removed = 0;
        int notComparable = 0;
        List<ChangedCase> changed = new ArrayList<>();
        List<ScatterPoint> scatter = new ArrayList<>();
        Map<String, int[]> dimCounts = new TreeMap<>(); // matched, worse, better, withinNoise
        Map<String, int[]> dimBase = new HashMap<>(); // passed, counted
        Map<String, int[]> dimCand = new HashMap<>();

        for (Map.Entry<String, Ev> en : c.entrySet()) {
            Ev ce = en.getValue();
            Ev be = b.get(en.getKey());
            Resolved rc = candClass.resolve(ce);
            if (be == null) {
                if (ce.counted()) {
                    added++;
                }
                continue;
            }
            if (!ce.counted() || !be.counted()) {
                notComparable++;
                continue;
            }
            matched++;
            String dim = rc.dimension();
            int[] dc = dimCounts.computeIfAbsent(dim, k -> new int[4]);
            int[] db = dimBase.computeIfAbsent(dim, k -> new int[2]);
            int[] dd = dimCand.computeIfAbsent(dim, k -> new int[2]);
            dc[0]++;
            db[1]++;
            dd[1]++;
            if (be.passed()) {
                db[0]++;
            }
            if (ce.passed()) {
                dd[0]++;
            }
            String change;
            if (be.passed() && !ce.passed()) {
                change = "WORSE";
                worse++;
                dc[1]++;
            } else if (!be.passed() && ce.passed()) {
                change = "BETTER";
                better++;
                dc[2]++;
            } else {
                change = "SAME";
                same++;
            }
            Double delta =
                    be.score() != null && ce.score() != null ? ce.score() - be.score() : null;
            boolean noise = false;
            if (!"SAME".equals(change) && "JUDGE".equals(ce.kind()) && delta != null) {
                noise = Math.abs(delta) <= band(cand, ce, config);
                if (noise) {
                    withinNoise++;
                    dc[3]++;
                }
            }
            if ("JUDGE".equals(ce.kind()) && be.score() != null && ce.score() != null) {
                scatter.add(new ScatterPoint(ce.key(), be.score(), ce.score(), change));
            }
            if (!"SAME".equals(change)) {
                boolean unchanged =
                        Objects.equals(be.actualOutput(), ce.actualOutput())
                                && be.actualOutput() != null;
                changed.add(
                        new ChangedCase(
                                ce.key(),
                                ce.caseId(),
                                caseNames.getOrDefault(ce.caseId(), ce.caseId()),
                                ce.metric(),
                                dim,
                                ce.kind(),
                                change,
                                noise,
                                be.score(),
                                ce.score(),
                                delta,
                                be.reason(),
                                ce.reason(),
                                be.sourceOrFresh(),
                                ce.sourceOrFresh(),
                                be.actualOutput(),
                                ce.actualOutput(),
                                unchanged));
            }
        }
        for (Map.Entry<String, Ev> en : b.entrySet()) {
            if (!c.containsKey(en.getKey()) && en.getValue().counted()) {
                removed++;
            }
        }
        changed.sort(
                Comparator.comparing(
                                (ChangedCase x) ->
                                        x.scoreDelta() == null ? -1.0 : Math.abs(x.scoreDelta()))
                        .reversed());
        if (scatter.size() > MAX_SCATTER) {
            List<ScatterPoint> keep = new ArrayList<>();
            for (ScatterPoint p : scatter) {
                if (!"SAME".equals(p.change())) {
                    keep.add(p);
                }
            }
            for (ScatterPoint p : scatter) {
                if ("SAME".equals(p.change()) && keep.size() < MAX_SCATTER) {
                    keep.add(p);
                }
            }
            scatter = keep;
        }

        List<DimensionCompare> dims = new ArrayList<>();
        for (Map.Entry<String, int[]> e : dimCounts.entrySet()) {
            int[] db = dimBase.get(e.getKey());
            int[] dd = dimCand.get(e.getKey());
            Double br = Rollups.rate(db[0], db[1] - db[0]);
            Double cr = Rollups.rate(dd[0], dd[1] - dd[0]);
            ReportConfig.DimensionConfig dc = config.dimension(e.getKey());
            dims.add(
                    new DimensionCompare(
                            e.getKey(),
                            dc.name(),
                            br,
                            cr,
                            br == null || cr == null ? null : cr - br,
                            dc.goal(),
                            e.getValue()[1],
                            e.getValue()[2],
                            e.getValue()[3],
                            e.getValue()[0]));
        }

        List<Note> notes = new ArrayList<>();
        JsonNode bh = base.run().env().path("configHash");
        JsonNode ch = cand.run().env().path("configHash");
        if (!bh.isMissingNode() && !ch.isMissingNode() && !bh.equals(ch)) {
            notes.add(
                    new Note(
                            "warn",
                            "CONFIG_HASH_DIFFERS",
                            "The two runs used different evaluation settings; compare with care."));
        }
        if (datasetsDiffer(base, cand)) {
            notes.add(
                    new Note(
                            "warn",
                            "DATASET_DIFFERS",
                            "The runs used different datasets; "
                                    + matched
                                    + " cases were matched."));
        }
        String bp = base.run().profile().path("name").asText("");
        String cp = cand.run().profile().path("name").asText("");
        if (!bp.equals(cp)) {
            notes.add(
                    new Note(
                            "info",
                            "PROFILE_DIFFERS",
                            "Baseline profile "
                                    + bp
                                    + ", candidate profile "
                                    + cp
                                    + "; some results may be carried over on either side."));
        }

        Rollup0 bo = overall(base, baseClass);
        Rollup0 co = overall(cand, candClass);
        return new CompareModel(
                base.run().runId(),
                basePicked,
                cand.run().runId(),
                basePicked,
                config.noiseBand,
                matched,
                worse,
                better,
                same,
                withinNoise,
                added,
                removed,
                notComparable,
                bo.rate,
                co.rate,
                dims,
                changed,
                envRows(base, cand),
                scatter,
                notes);
    }

    private record Rollup0(Double rate) {}

    private static Rollup0 overall(RunBundle r, Classify cl) {
        int p = 0;
        int f = 0;
        for (Ev e : r.evaluations()) {
            if (e.counted()) {
                if (e.passed()) {
                    p++;
                } else {
                    f++;
                }
            }
        }
        return new Rollup0(Rollups.rate(p, f));
    }

    private static double band(RunBundle cand, Ev e, ReportConfig config) {
        for (JsonNode j : cand.run().env().path("judges")) {
            if (e.judgeId() != null && e.judgeId().equals(j.path("id").asText())) {
                JsonNode nb = j.path("stats").path("noiseBand");
                if (nb.isNumber()) {
                    return nb.asDouble();
                }
            }
        }
        return config.noiseBand;
    }

    private static boolean datasetsDiffer(RunBundle a, RunBundle b) {
        Map<String, String> ha = datasetHashes(a);
        Map<String, String> hb = datasetHashes(b);
        return !ha.isEmpty() && !hb.isEmpty() && !ha.equals(hb);
    }

    private static Map<String, String> datasetHashes(RunBundle r) {
        Map<String, String> m = new TreeMap<>();
        for (JsonNode d : r.run().env().path("datasets")) {
            m.put(d.path("id").asText(), d.path("hash").asText(""));
        }
        return m;
    }

    private static Map<String, Ev> byKey(List<Ev> evs) {
        Map<String, Ev> m = new LinkedHashMap<>();
        for (Ev e : evs) {
            if (e.key() == null) {
                continue;
            }
            Ev prev = m.get(e.key());
            if (prev == null || (e.seq() != null && prev.seq() != null && e.seq() >= prev.seq())) {
                m.put(e.key(), e);
            }
        }
        return m;
    }

    private static Map<String, String> caseNames(RunBundle cand, RunBundle base) {
        Map<String, String> m = new HashMap<>();
        for (RunBundle r : List.of(base, cand)) {
            for (ScenarioDef s : r.scenarios()) {
                if (s.caseId() != null) {
                    m.put(s.caseId(), s.name() != null ? s.name() : s.id());
                }
            }
        }
        return m;
    }

    private static List<EnvRow> envRows(RunBundle base, RunBundle cand) {
        List<EnvRow> rows = new ArrayList<>();
        row(rows, "Branch", base.run().branch(), cand.run().branch());
        row(rows, "Commit", base.run().commit(), cand.run().commit());
        row(
                rows,
                "Profile",
                base.run().profile().path("name").asText(null),
                cand.run().profile().path("name").asText(null));
        for (String kind : List.of("agents", "judges", "datasets")) {
            Map<String, String> bm = describe(base.run().env().path(kind));
            Map<String, String> cm = describe(cand.run().env().path(kind));
            java.util.Set<String> ids = new java.util.TreeSet<>(bm.keySet());
            ids.addAll(cm.keySet());
            for (String id : ids) {
                row(rows, kind.substring(0, kind.length() - 1) + " " + id, bm.get(id), cm.get(id));
            }
        }
        row(
                rows,
                "eval4j",
                base.run().env().path("eval4jVersion").asText(null),
                cand.run().env().path("eval4jVersion").asText(null));
        return rows;
    }

    private static Map<String, String> describe(JsonNode arr) {
        Map<String, String> m = new TreeMap<>();
        for (JsonNode n : arr) {
            StringBuilder sb = new StringBuilder();
            for (String f :
                    List.of(
                            "provider",
                            "model",
                            "promptVersion",
                            "revision",
                            "temperature",
                            "samples")) {
                if (n.hasNonNull(f)) {
                    if (sb.length() > 0) {
                        sb.append(" · ");
                    }
                    sb.append(n.get(f).asText());
                }
            }
            m.put(n.path("id").asText(), sb.toString());
        }
        return m;
    }

    private static void row(List<EnvRow> rows, String field, String b, String c) {
        rows.add(new EnvRow(field, b, c, !Objects.equals(b, c)));
    }
}
