package io.github.llm4j.loom.generate;

import java.util.stream.Collectors;

/** {@code PLAN.md}: the plan as a document to read. */
public final class PlanMarkdown {

    private PlanMarkdown() {}

    public static String render(Plan p) {
        StringBuilder out = new StringBuilder();
        out.append("# Plan: ").append(p.name()).append("\n\n");
        if (p.summary() != null && !p.summary().isBlank())
            out.append(p.summary().strip()).append("\n\n");
        out.append("Fingerprint `")
                .append(PlanJson.hash(p))
                .append(
                        "`. Edit `plan.json` and run `weave generate --from-plan plan.json` to build it.\n\n");
        section(out, "Agents");
        if (p.agents().isEmpty()) out.append("- none\n");
        for (Plan.Agent a : p.agents())
            out.append("- **")
                    .append(a.name())
                    .append("** (")
                    .append(a.model())
                    .append("; ")
                    .append(
                            a.tools().isEmpty()
                                    ? "no tools"
                                    : "tools: " + String.join(", ", a.tools()))
                    .append("): ")
                    .append(a.role())
                    .append('\n');
        section(out, "Classifiers");
        if (p.classifiers().isEmpty()) out.append("- none\n");
        for (Plan.Classifier c : p.classifiers())
            out.append("- **")
                    .append(c.name())
                    .append("** (")
                    .append(c.backend())
                    .append("): ")
                    .append(c.role())
                    .append(
                            c.questions().isEmpty()
                                    ? ""
                                    : "; asks " + String.join(", ", c.questions()))
                    .append(
                            c.onUnsure() == null || c.onUnsure().isBlank()
                                    ? ""
                                    : "; unsure: " + c.onUnsure())
                    .append('\n');
        section(out, "Tools");
        if (p.tools().isEmpty()) out.append("- none\n");
        for (Plan.Tool t : p.tools())
            out.append("- **")
                    .append(t.name())
                    .append("** (")
                    .append(t.kind())
                    .append(", reach ")
                    .append(t.reach())
                    .append(t.stub() ? ", a stub you write" : "")
                    .append(')')
                    .append(t.reason() == null || t.reason().isBlank() ? "" : ": " + t.reason())
                    .append('\n');
        section(out, "People asked");
        if (p.humans().isEmpty()) out.append("- nobody\n");
        for (Plan.Human h : p.humans())
            out.append("- ").append(h.name()).append(": ").append(h.when()).append('\n');
        section(out, "Effects and approval");
        if (p.effects().isEmpty()) out.append("- none (results are returned, not sent)\n");
        for (Plan.Effect e : p.effects())
            out.append("- ")
                    .append(e.tool())
                    .append(" (")
                    .append(e.reach())
                    .append("): ")
                    .append(
                            "required".equals(e.approval())
                                    ? "a person approves each call"
                                    : "no approval, because " + e.because())
                    .append('\n');
        section(out, "Budget and failure");
        out.append("- Budget: ")
                .append(
                        p.budget() == null
                                ? "none"
                                : (p.budget().tokens() == null
                                                ? ""
                                                : p.budget().tokens() + " tokens")
                                        + (p.budget().calls() == null
                                                ? ""
                                                : " / " + p.budget().calls() + " calls")
                                        + " per run")
                .append('\n');
        out.append("- Personal data: ")
                .append(p.personalData() ? "yes (masked before it reaches a model)" : "no")
                .append('\n');
        out.append("- Failure: ")
                .append(p.failure() == null || p.failure().isBlank() ? "not stated" : p.failure())
                .append('\n');
        section(out, "Tests");
        if (p.tests().isEmpty()) out.append("- none planned\n");
        for (Plan.TestIdea t : p.tests())
            out.append("- ").append(t.name()).append(": ").append(t.what()).append('\n');
        if (!p.answers().isEmpty()) {
            section(out, "Settled with you");
            out.append(
                            p.answers().stream()
                                    .map(
                                            a ->
                                                    "- "
                                                            + a.question()
                                                            + " "
                                                            + a.answer()
                                                            + (a.defaulted() ? " (default)" : ""))
                                    .collect(Collectors.joining("\n")))
                    .append('\n');
        }
        return out.toString();
    }

    private static void section(StringBuilder out, String title) {
        out.append("\n## ").append(title).append("\n\n");
    }
}
