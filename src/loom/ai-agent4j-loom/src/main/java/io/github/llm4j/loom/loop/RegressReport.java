package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * The report of a regress, in text, JSON, Markdown, JUnit and a single HTML file. It states what
 * the sample is and never more than the sample supports: a share is a share of the runs sampled,
 * and is said to be of the population only for a random sample, with an interval.
 */
public final class RegressReport {

    private RegressReport() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int VERSION = 1;

    public static final String CANNOT_TELL =
            "What this cannot tell you: how a changed prompt makes a real model behave (only a live"
                    + " comparison on a sample, or weave eval, does); anything about inputs that never"
                    + " happened; and, because recorded model replies are one sample of something"
                    + " that is not deterministic, whether the same input would be answered the same"
                    + " way again. Effects were only described, so what other systems would do is unknown.";

    /** How many were sampled, in words that claim nothing about the rest. */
    static String sampleLine(Regress.Report r) {
        Sampler.Sample s = r.sample();
        int n = s.chosen().size();
        String how =
                s.chosen().size() == s.population()
                        ? "all " + n + " eligible runs"
                        : n
                                + " of "
                                + s.population()
                                + " eligible runs ("
                                + s.strategy().label()
                                + (s.strategy() == Sampler.Strategy.RANDOM
                                                || s.strategy() == Sampler.Strategy.STRATIFIED
                                        ? ", seed " + s.seed()
                                        : "")
                                + ")";
        return how + ", out of " + r.runsInStore() + " in the store";
    }

    /** A 95 % interval for a share in a random sample (Wilson), as "a to b %"; null otherwise. */
    static String interval(Regress.Report r, long hits) {
        Sampler.Sample s = r.sample();
        int n = s.chosen().size();
        if (s.strategy() != Sampler.Strategy.RANDOM || n == s.population() || n == 0) return null;
        double z = 1.96, p = (double) hits / n;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return String.format(
                "%.0f to %.0f %%",
                100 * Math.max(0, centre - half), 100 * Math.min(1, centre + half));
    }

    /** The cost of a typical run before and after, as estimated from the two scripts. */
    static List<String> costLines(Regress.Report r) {
        List<String> out = new java.util.ArrayList<>();
        for (var e : r.costAfter().entrySet()) {
            Regress.CostFigure was = r.costBefore().get(e.getKey());
            Regress.CostFigure now = e.getValue();
            if (was == null || was.typicalTokens() == 0) continue;
            double pct = 100.0 * (now.typicalTokens() - was.typicalTokens()) / was.typicalTokens();
            String money =
                    was.typicalCost() != null && now.typicalCost() != null
                            ? ", $"
                                    + was.typicalCost().toPlainString()
                                    + " to $"
                                    + now.typicalCost().toPlainString()
                            : "";
            out.add(
                    e.getKey()
                            + ": a typical run "
                            + was.typicalTokens()
                            + " to "
                            + now.typicalTokens()
                            + " tokens ("
                            + String.format("%+.0f", pct)
                            + " %)"
                            + money
                            + " (estimated from the prompts and settings of the two scripts; not measured)");
        }
        return out;
    }

    /**
     * The largest rise, in percent, of a typical run's cost between the two scripts; 0 when none.
     */
    public static double costUp(Regress.Report r) {
        double worst = 0;
        for (var e : r.costAfter().entrySet()) {
            Regress.CostFigure was = r.costBefore().get(e.getKey());
            if (was == null) continue;
            Regress.CostFigure now = e.getValue();
            double pct;
            if (was.typicalCost() != null
                    && now.typicalCost() != null
                    && was.typicalCost().signum() > 0)
                pct =
                        100
                                * now.typicalCost().subtract(was.typicalCost()).doubleValue()
                                / was.typicalCost().doubleValue();
            else if (was.typicalTokens() > 0)
                pct = 100.0 * (now.typicalTokens() - was.typicalTokens()) / was.typicalTokens();
            else continue;
            worst = Math.max(worst, pct);
        }
        return worst;
    }

    // ---- text

    public static String text(Regress.Report r, boolean showText, Masker masker) {
        StringBuilder b = new StringBuilder();
        int n = r.sample().chosen().size();
        b.append("Regress  ")
                .append(r.candidate())
                .append(" (candidate) vs ")
                .append(r.baseline())
                .append('\n');
        b.append("  compared ").append(sampleLine(r)).append("\n\n");
        row(b, "Same", r.count(Regress.Verdict.SAME), n);
        row(b, "Changed route", r.count(Regress.Verdict.CHANGED_ROUTE), n);
        row(b, "Changed result", r.count(Regress.Verdict.CHANGED_RESULT), n);
        row(b, "Needs a model", r.count(Regress.Verdict.NEEDS_MODEL), n);
        if (r.count(Regress.Verdict.NOT_COMPARED) > 0)
            row(b, "Not compared", r.count(Regress.Verdict.NOT_COMPARED), n);
        if (r.count(Regress.Verdict.DID_NOT_FINISH) > 0)
            row(b, "Did not finish", r.count(Regress.Verdict.DID_NOT_FINISH), n);
        if (r.count(Regress.Verdict.NEEDS_MODEL) > 0)
            b.append(
                    "    (a run is counted as needing a model when the candidate changes what a model is asked; it is never guessed. Use --models live with a cap to run them.)\n");
        if (r.spent() != null) {
            long live = r.liveCompared();
            b.append("\n  Compared with recorded answers: ")
                    .append(n - live - r.count(Regress.Verdict.NOT_COMPARED))
                    .append("   with live models: ")
                    .append(live)
                    .append(
                            " (those numbers mean different things: the second says how the candidate behaved with a model asked again)\n");
            b.append("  Spent on live models and judges: ")
                    .append(r.spent().tokens())
                    .append(" tokens in ")
                    .append(r.spent().calls())
                    .append(" calls")
                    .append(
                            r.spent().cost() == null
                                    ? " (the price of a model was not known)"
                                    : ", $"
                                            + r.spent()
                                                    .cost()
                                                    .setScale(4, java.math.RoundingMode.HALF_UP)
                                                    .toPlainString())
                    .append(" (measured)\n");
        }
        if (r.stoppedByCap())
            b.append(
                    "  STOPPED BY THE SPEND CAP: the runs marked not compared were left out; raise --max-cost to see them.\n");
        long changed = n - r.count(Regress.Verdict.SAME);
        String ci = interval(r, changed);
        if (ci != null)
            b.append("\n  The sample is random: the share of runs that differ is between ")
                    .append(ci)
                    .append(" of all eligible runs (95 % interval).\n");
        else if (n < r.sample().population())
            b.append(
                    "\n  The sample is not random, so these counts are about the runs sampled, not about production.\n");
        List<Regress.Comparison> unsafe = r.unsafe();
        b.append("\n  UNSAFE FLIPS  ")
                .append(unsafe.size() + r.approvedFlips().size())
                .append('\n');
        for (Regress.ApprovedFlip f : r.approvedFlips())
            b.append("    [approved] ")
                    .append(f.file())
                    .append(": ")
                    .append(f.name())
                    .append("   ")
                    .append(String.join("; ", f.failures()))
                    .append('\n');
        for (Regress.Comparison c : unsafe) {
            b.append("    ").append(c.run()).append("   ").append(String.join("; ", c.unsafe()));
            if (c.firstDifference() != null)
                b.append("   (first difference at ").append(c.firstDifference()).append(')');
            b.append('\n');
            if (showText && c.before() != null)
                b.append("      before: ")
                        .append(m(masker, c.before()))
                        .append("\n      after:  ")
                        .append(m(masker, c.after()))
                        .append('\n');
        }
        if (!r.comparisons().isEmpty() && r.count(Regress.Verdict.CHANGED_ROUTE) > 0) {
            b.append("\n  Where the changed routes now go:\n");
            Map<String, Long> to =
                    r.comparisons().stream()
                            .filter(c -> c.verdict() == Regress.Verdict.CHANGED_ROUTE)
                            .collect(
                                    java.util.stream.Collectors.groupingBy(
                                            c ->
                                                    c.candidateTakes() == null
                                                            ? "(a shorter path)"
                                                            : c.candidateTakes(),
                                            java.util.TreeMap::new,
                                            java.util.stream.Collectors.counting()));
            to.forEach((k, v) -> b.append("    ").append(v).append("  ").append(k).append('\n'));
        }
        if (!r.excluded().isEmpty()) {
            b.append("\n  Left out: ").append(r.excluded().size()).append(" run(s)\n");
            r.excluded().stream()
                    .collect(
                            java.util.stream.Collectors.groupingBy(
                                    Regress.Excluded::why,
                                    java.util.TreeMap::new,
                                    java.util.stream.Collectors.counting()))
                    .forEach(
                            (k, v) ->
                                    b.append("    ").append(v).append("  ").append(k).append('\n'));
        }
        if (r.approvedRun() > 0)
            b.append("\n  Approved captured cases: ")
                    .append(r.approvedRun())
                    .append(" run whatever the sample was; ")
                    .append(r.approvedFlips().size())
                    .append(" no longer pass.\n");
        List<String> costs = costLines(r);
        if (!costs.isEmpty()) {
            b.append("\n  Cost\n");
            costs.forEach(c -> b.append("    ").append(c).append('\n'));
        }
        b.append("\n  Sample by what the runs recorded (in the population, in the sample):\n");
        r.sample()
                .strata()
                .forEach(
                        (k, v) ->
                                b.append("    ")
                                        .append(v[0])
                                        .append(", ")
                                        .append(v[1])
                                        .append("  ")
                                        .append(k)
                                        .append('\n'));
        b.append("\n  ")
                .append(
                        r.storeUntouched()
                                ? "The store was only read and is as it was."
                                : "Something else wrote to the store while this ran (regress itself writes nothing).")
                .append('\n');
        b.append("  ").append(CANNOT_TELL).append('\n');
        return b.toString();
    }

    private static String m(Masker masker, String s) {
        return masker == null ? "(masking is off)" : masker.mask(s);
    }

    private static void row(StringBuilder b, String label, long k, int n) {
        b.append(String.format("  %-16s %4d  (of %d sampled)%n", label, k, n));
    }

    // ---- json

    public static String json(Regress.Report r, boolean showText, Masker masker) throws Exception {
        ObjectNode o = JSON.createObjectNode();
        o.put("version", VERSION);
        o.put("candidate", r.candidate());
        o.put("baseline", r.baseline());
        ObjectNode sample = o.putObject("sample");
        sample.put("strategy", r.sample().strategy().label());
        sample.put("seed", r.sample().seed());
        sample.put("population", r.sample().population());
        sample.put("size", r.sample().chosen().size());
        sample.put("runsInStore", r.runsInStore());
        sample.put("random", r.sample().strategy() == Sampler.Strategy.RANDOM);
        ObjectNode strata = sample.putObject("strata");
        r.sample()
                .strata()
                .forEach(
                        (k, v) -> strata.putObject(k).put("population", v[0]).put("sampled", v[1]));
        ObjectNode counts = o.putObject("counts");
        for (Regress.Verdict v : Regress.Verdict.values())
            counts.put(v.name().toLowerCase(), r.count(v));
        counts.put("unsafe", r.unsafe().size() + r.approvedFlips().size());
        ArrayNode runs = o.putArray("runs");
        for (Regress.Comparison c : r.comparisons()) {
            ObjectNode x = runs.addObject();
            x.put("run", c.run());
            x.put("verdict", c.verdict().name().toLowerCase());
            x.put("stratum", c.stratum());
            if (c.firstDifference() != null) x.put("firstDifference", c.firstDifference());
            if (c.candidateTakes() != null) x.put("candidateTakes", c.candidateTakes());
            if (c.detail() != null && !c.detail().isEmpty()) x.put("detail", c.detail());
            ArrayNode u = x.putArray("unsafe");
            c.unsafe().forEach(u::add);
            if (showText && c.before() != null) {
                x.put("before", m(masker, c.before()));
                x.put("after", m(masker, c.after()));
            }
        }
        ArrayNode ex = o.putArray("excluded");
        r.excluded().forEach(e -> ex.addObject().put("run", e.run()).put("why", e.why()));
        o.put("storeUntouched", r.storeUntouched());
        o.put("stoppedByCap", r.stoppedByCap());
        if (r.spent() != null) {
            ObjectNode sp = o.putObject("spent");
            sp.put("tokens", r.spent().tokens());
            sp.put("calls", r.spent().calls());
            if (r.spent().cost() != null) sp.put("cost", r.spent().cost().toPlainString());
            sp.put("liveCompared", r.liveCompared());
        }
        ArrayNode approved = o.putArray("approvedFlips");
        r.approvedFlips()
                .forEach(
                        f -> {
                            ObjectNode a = approved.addObject();
                            a.put("file", f.file());
                            a.put("case", f.name());
                            ArrayNode fl = a.putArray("failures");
                            f.failures().forEach(fl::add);
                        });
        o.put("approvedCasesRun", r.approvedRun());
        ArrayNode cost = o.putArray("cost");
        costLines(r).forEach(cost::add);
        o.put("cannotTell", CANNOT_TELL);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n";
    }

    // ---- markdown, for a pull request comment

    public static String markdown(Regress.Report r) {
        StringBuilder b = new StringBuilder();
        int n = r.sample().chosen().size();
        b.append("### Regress: `").append(r.candidate()).append("`\n\n");
        b.append("Compared ")
                .append(sampleLine(r))
                .append(" against ")
                .append(r.baseline())
                .append(".\n\n");
        b.append("| | runs |\n|---|---|\n");
        for (Regress.Verdict v : Regress.Verdict.values())
            b.append("| ")
                    .append(v.name().toLowerCase().replace('_', ' '))
                    .append(" | ")
                    .append(r.count(v))
                    .append(" of ")
                    .append(n)
                    .append(" sampled |\n");
        b.append("\n**Unsafe flips: ").append(r.unsafe().size()).append("**\n");
        for (Regress.Comparison c : r.unsafe())
            b.append("- `")
                    .append(c.run())
                    .append("`: ")
                    .append(String.join("; ", c.unsafe()))
                    .append('\n');
        if (n < r.sample().population() && r.sample().strategy() != Sampler.Strategy.RANDOM)
            b.append(
                    "\n_The sample is not random: these counts are about the runs sampled, not about production._\n");
        b.append("\n_").append(CANNOT_TELL).append("_\n");
        return b.toString();
    }

    // ---- junit: one test case per compared run, failing when it flipped unsafely

    public static String junit(Regress.Report r) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        long failures = r.unsafe().size();
        b.append("<testsuite name=\"regress ")
                .append(esc(r.candidate()))
                .append("\" tests=\"")
                .append(r.comparisons().size())
                .append("\" failures=\"")
                .append(failures)
                .append("\">\n");
        for (Regress.Comparison c : r.comparisons()) {
            b.append("  <testcase classname=\"regress\" name=\"")
                    .append(esc(c.run()))
                    .append(" ")
                    .append(c.verdict().name().toLowerCase())
                    .append("\"");
            if (c.isUnsafe()) {
                b.append(">\n    <failure message=\"")
                        .append(esc(String.join("; ", c.unsafe())))
                        .append("\"/>\n  </testcase>\n");
            } else b.append("/>\n");
        }
        return b.append("</testsuite>\n").toString();
    }

    // ---- html: one file, nothing remote, nothing built from run text

    public static String html(Regress.Report r) {
        StringBuilder b = new StringBuilder();
        b.append(
                "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        b.append("<title>Regress ").append(esc(r.candidate())).append("</title><style>");
        b.append(
                ":root{color-scheme:light dark;--bg:#fff;--fg:#1b1f23;--mute:#59636e;--bad:#b42318;--line:#d0d7de}");
        b.append(
                "@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mute:#8d96a0;--bad:#ff7b72;--line:#30363d}}");
        b.append(
                "body{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;margin:0 auto;padding:24px 16px;max-width:60rem}");
        b.append(
                "table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left}.bad{color:var(--bad);font-weight:600}.mute{color:var(--mute)}");
        b.append("</style></head><body><h1>Regress: ").append(esc(r.candidate())).append("</h1>");
        b.append("<p>Compared ")
                .append(esc(sampleLine(r)))
                .append(" against ")
                .append(esc(r.baseline()))
                .append(".</p><table><tr><th>Outcome</th><th>Runs</th></tr>");
        for (Regress.Verdict v : Regress.Verdict.values())
            b.append("<tr><td>")
                    .append(esc(v.name().toLowerCase().replace('_', ' ')))
                    .append("</td><td>")
                    .append(r.count(v))
                    .append("</td></tr>");
        b.append("</table><h2 class=\"bad\">Unsafe flips: ")
                .append(r.unsafe().size())
                .append("</h2><ul>");
        for (Regress.Comparison c : r.unsafe())
            b.append("<li><code>")
                    .append(esc(c.run()))
                    .append("</code> ")
                    .append(esc(String.join("; ", c.unsafe())))
                    .append("</li>");
        b.append("</ul>");
        if (!r.approvedFlips().isEmpty()) {
            b.append("<h2 class=\"bad\">Approved cases that no longer pass</h2><ul>");
            for (Regress.ApprovedFlip f : r.approvedFlips())
                b.append("<li>[approved] <code>")
                        .append(esc(f.file()))
                        .append("</code> ")
                        .append(esc(f.name()))
                        .append("</li>");
            b.append("</ul>");
        }
        if (r.stoppedByCap())
            b.append(
                    "<p class=\"bad\">Stopped by the spend cap: runs marked not compared were left out.</p>");
        for (String line : costLines(r))
            b.append("<p class=\"mute\">").append(esc(line)).append("</p>");
        b.append("<p class=\"mute\">").append(esc(CANNOT_TELL)).append("</p></body></html>");
        return b.toString();
    }

    static String esc(String s) {
        return s == null
                ? ""
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&#39;");
    }
}
