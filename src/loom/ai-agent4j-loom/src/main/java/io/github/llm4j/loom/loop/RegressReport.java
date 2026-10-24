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
        if (r.count(Regress.Verdict.DID_NOT_FINISH) > 0)
            row(b, "Did not finish", r.count(Regress.Verdict.DID_NOT_FINISH), n);
        if (r.count(Regress.Verdict.NEEDS_MODEL) > 0)
            b.append(
                    "    (a run is counted as needing a model when the candidate changes what a model is asked; it is never guessed. Use --models live with a cap to run them.)\n");
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
        b.append("\n  UNSAFE FLIPS  ").append(unsafe.size()).append('\n');
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
        counts.put("unsafe", r.unsafe().size());
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
        b.append("</ul><p class=\"mute\">").append(esc(CANNOT_TELL)).append("</p></body></html>");
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
