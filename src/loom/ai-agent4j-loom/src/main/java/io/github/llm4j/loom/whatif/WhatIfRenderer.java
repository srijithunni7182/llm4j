package io.github.llm4j.loom.whatif;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A {@link WhatIf.Outcome} as text, Markdown or JSON (version 1). Run text is shown with control
 * characters escaped.
 */
public final class WhatIfRenderer {

    private WhatIfRenderer() {}

    public static final int VERSION = 1;

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static String safe(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20
                    || c == 0x7f
                    || (c >= 0x80 && c < 0xa0)
                    || c == 0x202e
                    || (c >= 0x2066 && c <= 0x2069)) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String shown(Object value) {
        String s = safe(value);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public static String json(WhatIf.Outcome o) {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", VERSION);
        ObjectNode at = n.putObject("at");
        at.put("step", o.at()).put("statement", o.atStatement());
        ArrayNode changes = n.putArray("changes");
        for (WhatIf.Change c : o.changes()) {
            ObjectNode x = changes.addObject();
            x.put("kind", c.kind()).put("target", c.target()).put("value", c.value());
            if (c.times() > 1) x.put("times", c.times());
        }
        n.put("models", o.models());
        n.put("originalStatus", o.original().status());
        n.put("candidateStatus", o.candidate().status());
        if (!o.candidate().reason().isBlank()) n.put("candidateReason", o.candidate().reason());
        ObjectNode diff = n.putObject("diff");
        if (o.diff().firstDifference() != null)
            diff.put("firstDifference", o.diff().firstDifference());
        o.diff().onlyOriginal().forEach(diff.putArray("onlyOriginal")::add);
        if (!diff.has("onlyOriginal")) diff.putArray("onlyOriginal");
        ArrayNode onlyCandidate = diff.putArray("onlyCandidate");
        o.diff().onlyCandidate().forEach(onlyCandidate::add);
        ArrayNode both = diff.putArray("both");
        o.diff().both().forEach(both::add);
        ObjectNode result = n.putObject("result");
        result.set("original", JSON.valueToTree(o.originalResult()));
        result.set("candidate", JSON.valueToTree(o.candidateResult()));
        if (o.need() != null) {
            ObjectNode need = n.putObject("need");
            need.put("step", o.need().step()).put("statement", o.need().statement());
            need.put("what", o.need().what()).put("because", o.need().because());
        }
        ObjectNode effects = n.putObject("effects");
        effects.put("unsafe", o.effects().unsafe());
        effectList(effects.putArray("onlyOriginal"), o.effects().onlyOriginal());
        effectList(effects.putArray("onlyCandidate"), o.effects().onlyCandidate());
        ArrayNode notes = n.putArray("notes");
        o.notes().forEach(notes::add);
        try {
            return JSON.writeValueAsString(n) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void effectList(ArrayNode into, java.util.List<WhatIf.Effect> list) {
        for (WhatIf.Effect e : list)
            into.addObject()
                    .put("step", e.step())
                    .put("statement", e.statement())
                    .put("via", e.via());
    }

    public static String text(WhatIf.Outcome o) {
        StringBuilder out = new StringBuilder();
        out.append("What if, at ")
                .append(safe(o.atStatement()))
                .append(" (")
                .append(o.at())
                .append("):\n");
        for (WhatIf.Change c : o.changes())
            out.append("  - ").append(safe(c.describe())).append('\n');
        out.append("  models: ")
                .append(
                        o.models().equals("recorded")
                                ? "recorded answers are reused where the question is unchanged; nothing else is guessed"
                                : "only the answers given above; nothing is guessed")
                .append("\n\n");
        out.append("Original: ").append(o.original().status());
        if (o.originalResult() != null)
            out.append(", result ").append('"').append(shown(o.originalResult())).append('"');
        out.append("\nCandidate: ").append(o.candidate().status());
        if (!o.candidate().reason().isBlank() && !o.candidate().status().equals("completed"))
            out.append(" (").append(safe(o.candidate().reason())).append(")");
        if (o.candidateResult() != null)
            out.append(", result ").append('"').append(shown(o.candidateResult())).append('"');
        out.append("\n\n");
        if (o.diff().firstDifference() == null) out.append("The two ran alike from there.\n");
        else out.append("They part: ").append(safe(o.diff().firstDifference())).append(".\n");
        list(out, "Only in the original", o.diff().onlyOriginal());
        list(out, "Only in the candidate", o.diff().onlyCandidate());
        if (o.need() != null)
            out.append("\nThe candidate needs ")
                    .append(safe(o.need().what()))
                    .append(" at ")
                    .append(safe(o.need().statement()))
                    .append(" (")
                    .append(o.need().step())
                    .append("): ")
                    .append(safe(o.need().because()))
                    .append(". Give it with --answer, or ask for live models.\n");
        if (!o.effects().onlyCandidate().isEmpty() || !o.effects().onlyOriginal().isEmpty()) {
            out.append('\n');
            for (WhatIf.Effect e : o.effects().onlyCandidate())
                out.append("NEW in the candidate: ")
                        .append(safe(e.statement()))
                        .append(" may use ")
                        .append(safe(e.via()))
                        .append(" — an effect the original did not cause.\n");
            for (WhatIf.Effect e : o.effects().onlyOriginal())
                out.append("No longer in the candidate: ")
                        .append(safe(e.statement()))
                        .append(" may use ")
                        .append(safe(e.via()))
                        .append(".\n");
        }
        for (String n : o.notes()) out.append('\n').append(safe(n)).append('\n');
        return out.toString();
    }

    private static void list(StringBuilder out, String title, java.util.List<String> items) {
        if (items.isEmpty()) return;
        out.append('\n').append(title).append(" (").append(items.size()).append("):\n");
        int shown = Math.min(items.size(), 20);
        for (int i = 0; i < shown; i++) out.append("  ").append(safe(items.get(i))).append('\n');
        if (items.size() > shown)
            out.append("  … and ").append(items.size() - shown).append(" more\n");
    }

    public static String markdown(WhatIf.Outcome o) {
        StringBuilder out = new StringBuilder("# What if\n\n");
        out.append("At `")
                .append(md(o.atStatement()))
                .append("` (")
                .append(o.at())
                .append("):\n\n");
        for (WhatIf.Change c : o.changes()) out.append("- ").append(md(c.describe())).append('\n');
        out.append("\n| | Status | Result |\n|---|---|---|\n");
        out.append("| Original | ")
                .append(o.original().status())
                .append(" | ")
                .append(md(shown(o.originalResult())))
                .append(" |\n");
        out.append("| Candidate | ")
                .append(o.candidate().status())
                .append(" | ")
                .append(md(shown(o.candidateResult())))
                .append(" |\n\n");
        out.append(
                o.diff().firstDifference() == null
                        ? "The two ran alike from there.\n"
                        : "**They part:** " + md(o.diff().firstDifference()) + ".\n");
        mdList(out, "Only in the original", o.diff().onlyOriginal());
        mdList(out, "Only in the candidate", o.diff().onlyCandidate());
        if (o.need() != null)
            out.append("\n**Needs:** ")
                    .append(md(o.need().what()))
                    .append(" at ")
                    .append(md(o.need().statement()))
                    .append(" — ")
                    .append(md(o.need().because()))
                    .append('\n');
        for (WhatIf.Effect e : o.effects().onlyCandidate())
            out.append("\n**New effect in the candidate:** ")
                    .append(md(e.statement()))
                    .append(" may use ")
                    .append(md(e.via()))
                    .append('\n');
        return out.toString();
    }

    private static void mdList(StringBuilder out, String title, java.util.List<String> items) {
        if (items.isEmpty()) return;
        out.append("\n**").append(title).append("**\n\n");
        for (String i : items) out.append("- ").append(md(i)).append('\n');
    }

    private static String md(String s) {
        return safe(s).replace("|", "\\|")
                .replace("`", "'")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
