package io.github.llm4j.loom.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Why.Report} as text, Markdown or JSON. Whatever a run held (a reply, a classifier's
 * answer, a name) is data: control characters are shown as escapes so a value cannot move the
 * cursor, recolour the terminal or end a line early.
 */
public final class WhyRenderer {

    private WhyRenderer() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static String json(Why.Report report) {
        try {
            return JSON.writeValueAsString(report) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The text with control characters made visible. */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20
                    || c == 0x7f
                    || (c >= 0x80 && c < 0xa0)
                    || c == 0x202e
                    || c == 0x2066
                    || c == 0x2067
                    || c == 0x2068
                    || c == 0x2069) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String text(Why.Report r) {
        StringBuilder out = new StringBuilder();
        out.append("Run of ").append(safe(r.workflow())).append(" · ").append(r.status());
        if (!r.reason().isBlank() && !r.status().equals("completed"))
            out.append(" (").append(safe(r.reason())).append(")");
        out.append("\n\n");
        Map<String, Integer> numbered = new HashMap<>();
        for (Why.Entry e : r.entries()) numbered.putIfAbsent(e.step(), e.n());
        for (Why.Entry e : r.entries()) {
            out.append(String.format("%2d  ", e.n()));
            out.append(e.line() > 0 ? "line " + e.line() : "      ").append("  ");
            out.append(safe(e.statement()));
            String verdict = verdict(e);
            if (!verdict.isEmpty()) out.append("  → ").append(verdict);
            if (e.generation() > 1) out.append("   (attempt ").append(e.generation()).append(")");
            out.append('\n');
            for (Why.Operand o : e.operands()) {
                out.append("      ")
                        .append(safe(o.expr()))
                        .append(" = \"")
                        .append(safe(o.value()))
                        .append('"');
                if (o.length() > o.value().length())
                    out.append(" (").append(o.length()).append(" chars)");
                if (o.boundBy() != null) {
                    Integer at = numbered.get(o.boundStep());
                    out.append("  (bound ")
                            .append(at == null ? "at line " + o.boundLine() : "at step " + at)
                            .append(" by ")
                            .append(safe(o.boundBy()))
                            .append(')');
                } else {
                    out.append(
                            "input".equals(o.origin())
                                    ? "  (an input of the run)"
                                    : "  (not set at this point)");
                }
                out.append('\n');
            }
            if (e.kind().equals("classify") && !e.detail().isEmpty()) {
                StringBuilder line = new StringBuilder("      ");
                for (Map.Entry<String, Object> d : e.detail().entrySet()) {
                    if (d.getKey().endsWith("_confidence")
                            || d.getKey().endsWith("_probability")
                            || d.getKey().equals("calibrated")
                            || d.getKey().equals("backend")
                            || d.getKey().equals("model")) continue;
                    line.append(safe(d.getKey()))
                            .append(" = ")
                            .append(safe(String.valueOf(d.getValue())));
                    Object conf = e.detail().get(d.getKey() + "_confidence");
                    if (conf != null)
                        line.append(" (").append(safe(String.valueOf(conf))).append(')');
                    line.append("   ");
                }
                out.append(line.toString().stripTrailing()).append('\n');
            }
            for (String n : e.notTaken())
                out.append("      not taken: ").append(safe(n)).append('\n');
        }
        if (!r.notes().isEmpty()) {
            out.append('\n');
            for (String n : r.notes()) out.append(safe(n)).append('\n');
        }
        if (r.answer() != null) {
            out.append('\n').append(safe(r.answer().text())).append('\n');
            for (String w : r.answer().wouldNeed())
                out.append("  needs: ").append(safe(w)).append('\n');
        }
        return out.toString();
    }

    private static String verdict(Why.Entry e) {
        if (e.taken() == null) return e.summary() == null ? "" : safe(e.summary());
        StringBuilder v = new StringBuilder();
        if (e.result() != null) v.append(e.result() ? "TRUE" : "false").append("  → ");
        v.append(safe(e.taken()));
        Object exit = e.detail().get("exit");
        Object done = e.detail().get("done");
        if (e.kind().equals("loop") && done != null && exit == null) {
            int rounds = ((Number) done).intValue();
            Object max = e.detail().get("max");
            if (max != null && rounds >= ((Number) max).intValue()) v.append(" (out of rounds)");
            else
                v.append(" (round ")
                        .append(rounds + 1)
                        .append(max == null ? "" : " of " + max)
                        .append(')');
        }
        if (e.kind().equals("loop") && exit != null && !"condition".equals(exit))
            v.append(" (left by ")
                    .append(exit)
                    .append(" after ")
                    .append(e.detail().get("rounds"))
                    .append(" rounds)");
        if (e.kind().equals("for each"))
            v.append(" (").append(e.detail().get("items")).append(" items)");
        if (e.kind().equals("failure"))
            v.append(" (")
                    .append(safe(String.valueOf(e.detail().getOrDefault("text", ""))))
                    .append(")");
        return v.toString();
    }

    public static String markdown(Why.Report r) {
        StringBuilder out = new StringBuilder();
        out.append("# Why the run went where it did\n\n");
        out.append("**").append(md(r.workflow())).append("** · ").append(r.status());
        if (!r.reason().isBlank() && !r.status().equals("completed"))
            out.append(" (").append(md(r.reason())).append(')');
        out.append("\n\n| # | Line | Statement | Result | Values |\n|---|---|---|---|---|\n");
        for (Why.Entry e : r.entries()) {
            out.append("| ")
                    .append(e.n())
                    .append(" | ")
                    .append(e.line() > 0 ? e.line() : "")
                    .append(" | `")
                    .append(md(e.statement()))
                    .append("` | ")
                    .append(md(verdict(e)))
                    .append(" | ");
            List<String> values = new java.util.ArrayList<>();
            for (Why.Operand o : e.operands())
                values.add(
                        "`"
                                + md(o.expr())
                                + "` = \""
                                + md(o.value())
                                + "\""
                                + (o.boundBy() == null
                                        ? ""
                                        : " (bound by " + md(o.boundBy()) + ")"));
            out.append(String.join("<br>", values)).append(" |\n");
        }
        if (!r.notes().isEmpty()) {
            out.append('\n');
            for (String n : r.notes()) out.append("- ").append(md(n)).append('\n');
        }
        if (r.answer() != null) {
            out.append("\n**")
                    .append(md(r.answer().question()))
                    .append("?** ")
                    .append(md(r.answer().text()))
                    .append('\n');
            for (String w : r.answer().wouldNeed())
                out.append("- needs: `").append(md(w)).append("`\n");
        }
        return out.toString();
    }

    /** Text safe to put in a Markdown table cell. */
    private static String md(String s) {
        return safe(s).replace("|", "\\|")
                .replace("`", "'")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
