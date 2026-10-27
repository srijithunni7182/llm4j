package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The results of a run as text for a person, JSON for a tool (versioned), JUnit XML for a CI
 * server, or TAP. Everything a case printed or failed with is data: it is escaped for the format
 * and cannot break it, whatever it contains.
 */
public final class Reports {

    /** The version of the JSON report; it changes when a field is removed or changes meaning. */
    public static final int JSON_VERSION = 1;

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Reports() {}

    /** The whole run: what was run, the cases, and how much of the graph they reached. */
    public record Run(
            String script,
            List<TestRunner.CaseResult> cases,
            Coverage coverage,
            long millis,
            Long seed) {

        public long failed() {
            return cases.stream().filter(c -> !c.passed()).count();
        }

        public long passed() {
            return cases.size() - failed();
        }
    }

    // ---- text

    public static String text(Run r) {
        StringBuilder b = new StringBuilder();
        if (r.seed() != null) b.append("shuffled with seed ").append(r.seed()).append('\n');
        for (TestRunner.CaseResult c : r.cases()) {
            b.append(
                    String.format(
                            "%-5s %s  (%d ms)%n",
                            c.passed() ? "ok" : "FAIL", c.name(), c.millis()));
            for (String f : c.failures())
                b.append("        - ").append(f.replace("\n", "\n          ")).append('\n');
            for (String n : c.notes()) b.append("        ~ lenient: ").append(n).append('\n');
        }
        b.append(r.passed())
                .append(" passed, ")
                .append(r.failed())
                .append(" failed in ")
                .append(time(r.millis()));
        if (r.coverage() != null) {
            Coverage v = r.coverage();
            int missed = v.total() - v.covered();
            b.append(" · nodes covered ").append(v.covered()).append('/').append(v.total());
            if (missed > 0) b.append(" (").append(missed).append(" uncovered: see --coverage)");
        }
        return b.append('\n').toString();
    }

    /** The nodes nobody reached, each with a hint, for {@code --coverage}. */
    public static String uncovered(Coverage v) {
        StringBuilder b = new StringBuilder();
        List<Coverage.Missed> missed = v.missed();
        b.append("Nodes covered ")
                .append(v.covered())
                .append('/')
                .append(v.total())
                .append(" (")
                .append(v.percent())
                .append(" %)\n");
        List<Coverage.Branch> open = v.branches().stream().filter(x -> !x.covered()).toList();
        if (!open.isEmpty()) {
            b.append("Blocks never run: ");
            List<String> names = new ArrayList<>();
            for (Coverage.Branch x : open)
                names.add(x.workflow() + "/" + x.owner() + " " + x.branch());
            b.append(String.join(", ", names)).append('\n');
        }
        for (Coverage.Missed m : missed)
            b.append(
                            String.format(
                                    "  %s %-5s line %-4s %s%n",
                                    m.workflow(),
                                    m.id(),
                                    m.line() == 0 ? "-" : String.valueOf(m.line()),
                                    m.label()))
                    .append("      # to reach this: ")
                    .append(m.how())
                    .append('\n');
        if (missed.isEmpty()) b.append("  every node was reached\n");
        return b.toString();
    }

    private static String time(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    // ---- json

    public static String json(Run r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", JSON_VERSION);
        root.put("script", r.script());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("passed", r.passed());
        summary.put("failed", r.failed());
        summary.put("skipped", 0);
        summary.put("millis", r.millis());
        if (r.seed() != null) summary.put("seed", r.seed());
        root.put("summary", summary);
        if (r.coverage() != null) {
            Map<String, Object> cov = new LinkedHashMap<>();
            cov.put("covered", r.coverage().covered());
            cov.put("total", r.coverage().total());
            cov.put("percent", r.coverage().percent());
            List<Object> missed = new ArrayList<>();
            for (Coverage.Missed m : r.coverage().missed()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("workflow", m.workflow());
                x.put("node", m.id());
                x.put("line", m.line());
                x.put("label", m.label());
                x.put("toReach", m.how());
                missed.add(x);
            }
            cov.put("uncovered", missed);
            root.put("coverage", cov);
        }
        List<Object> cases = new ArrayList<>();
        for (TestRunner.CaseResult c : r.cases()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("file", c.file());
            x.put("name", c.name());
            x.put("passed", c.passed());
            x.put("millis", c.millis());
            x.put("tags", c.tags());
            x.put("failures", c.failures());
            x.put("notes", c.notes());
            cases.add(x);
        }
        root.put("cases", cases);
        try {
            return JSON.writeValueAsString(root) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- junit

    public static String junit(Run r) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<testsuites>\n");
        b.append("  <testsuite name=\"")
                .append(xml(r.script()))
                .append("\" tests=\"")
                .append(r.cases().size())
                .append("\" failures=\"")
                .append(r.failed())
                .append("\" errors=\"0\" skipped=\"0\" time=\"")
                .append(seconds(r.millis()))
                .append("\">\n");
        for (TestRunner.CaseResult c : r.cases()) {
            b.append("    <testcase classname=\"")
                    .append(xml(c.file()))
                    .append("\" name=\"")
                    .append(xml(c.name()))
                    .append("\" time=\"")
                    .append(seconds(c.millis()))
                    .append('"');
            if (c.passed()) b.append("/>\n");
            else {
                b.append(">\n      <failure message=\"")
                        .append(xml(c.failures().get(0)))
                        .append("\">")
                        .append(xml(String.join("\n", c.failures())))
                        .append("</failure>\n    </testcase>\n");
            }
        }
        return b.append("  </testsuite>\n</testsuites>\n").toString();
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0);
    }

    /** Text safe for XML 1.0: markup escaped and characters it cannot hold removed. */
    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean allowed =
                    cp == 0x9
                            || cp == 0xA
                            || cp == 0xD
                            || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD)
                            || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!allowed) continue;
            switch (cp) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.appendCodePoint(cp);
            }
        }
        return b.toString();
    }

    // ---- tap

    public static String tap(Run r) {
        StringBuilder b =
                new StringBuilder("TAP version 13\n1..").append(r.cases().size()).append('\n');
        int n = 0;
        for (TestRunner.CaseResult c : r.cases()) {
            n++;
            b.append(c.passed() ? "ok " : "not ok ")
                    .append(n)
                    .append(" - ")
                    .append(line(c.name()))
                    .append('\n');
            for (String f : c.failures())
                for (String l : f.split("\n")) b.append("  # ").append(line(l)).append('\n');
            for (String note : c.notes()) b.append("  # lenient: ").append(line(note)).append('\n');
        }
        return b.toString();
    }

    private static String line(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').replace("#", "\\#");
    }
}
