package io.github.llm4j.loom.generate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The card at the end: what was made, what held, what it cost, what was not checked, and what to do
 * next.
 */
public final class Summary {

    private Summary() {}

    public static final String NOT_CHECKED =
            "Not checked: how good the answers are with a real model (that is weave eval), whether the business rules are the ones you meant, and the code inside any tool stub.";

    public static String text(
            Plan plan,
            String where,
            List<String> files,
            List<GateResult> gates,
            List<String> toolFixes,
            Spend spend,
            int attempts,
            List<String> next) {
        return text(plan, where, files, gates, toolFixes, List.of(), spend, attempts, next);
    }

    /**
     * @param review what the review says about the finished project, one line each (findings that
     *     are advice, and what a run can cost)
     */
    public static String text(
            Plan plan,
            String where,
            List<String> files,
            List<GateResult> gates,
            List<String> toolFixes,
            List<String> review,
            Spend spend,
            int attempts,
            List<String> next) {
        StringBuilder sb = new StringBuilder();
        sb.append("✓ Made ")
                .append(plan.name())
                .append(" in ")
                .append(where)
                .append(" (")
                .append(files.size())
                .append(" files, ")
                .append(attempts)
                .append(attempts == 1 ? " attempt" : " attempts")
                .append(")\n\n");
        sb.append("What it does\n");
        for (Plan.Agent a : plan.agents())
            sb.append("  - ").append(a.name()).append(": ").append(a.role()).append('\n');
        for (Plan.Classifier c : plan.classifiers())
            sb.append("  - ")
                    .append(c.name())
                    .append(" (classifier): ")
                    .append(c.role())
                    .append('\n');
        for (Plan.Human h : plan.humans())
            sb.append("  - ").append(h.name()).append(" decides: ").append(h.when()).append('\n');
        for (Plan.Effect e : plan.effects())
            sb.append("  - ")
                    .append(e.tool())
                    .append(" (")
                    .append(e.reach())
                    .append("): ")
                    .append(
                            "required".equals(e.approval())
                                    ? "a person approves each call"
                                    : "no approval, because " + e.because())
                    .append('\n');
        if (plan.budget() != null)
            sb.append("  - budget: ")
                    .append(
                            plan.budget().tokens() == null
                                    ? ""
                                    : plan.budget().tokens() + " tokens")
                    .append(
                            plan.budget().calls() == null
                                    ? ""
                                    : " / " + plan.budget().calls() + " calls")
                    .append(" per run\n");
        sb.append("\nGates\n");
        for (GateResult g : gates)
            sb.append("  ")
                    .append(g.passed() ? "✓ " : "✗ ")
                    .append(g.gate())
                    .append(' ')
                    .append(g.name())
                    .append('\n');
        for (String f : toolFixes) sb.append("  fixed by the tool: ").append(f).append('\n');
        if (!review.isEmpty()) {
            sb.append("\nReview (advice, not a gate)\n");
            for (String r : review) sb.append("  ").append(r).append('\n');
        }
        sb.append("\nGeneration cost: ")
                .append(spend.calls())
                .append(" model calls, ")
                .append(spend.tokens())
                .append(" tokens");
        BigDecimal cost = spend.cost();
        sb.append(
                        cost == null
                                ? " (price of the model unknown)"
                                : ", $" + cost.setScale(4, RoundingMode.HALF_UP).toPlainString())
                .append("\n\n");
        sb.append(NOT_CHECKED).append("\n\nNext\n");
        int i = 1;
        for (String n : next) sb.append("  ").append(i++).append(". ").append(n).append('\n');
        return sb.toString();
    }

    /** The three things to do next in a new project. */
    public static List<String> nextSteps(String where) {
        return List.of(
                "cd "
                        + where
                        + " && weave run --help   (read how to run it; the inputs are in README.md)",
                "weave eval --init   (make a golden dataset, then weave eval --mock to see the wiring cost nothing)",
                "weave review "
                        + "main.loom"
                        + "   (read the findings that are advice, not gates)");
    }

    /** The review of a finished project in a few lines: findings by severity, and token costs. */
    public static List<String> reviewLines(DeltaGate.Snapshot snapshot) {
        List<String> out = new java.util.ArrayList<>();
        long high = 0, medium = 0, rest = 0;
        for (DeltaGate.Found f : snapshot.open())
            switch (f.severity()) {
                case HIGH -> high++;
                case MEDIUM -> medium++;
                default -> rest++;
            }
        out.add(
                snapshot.open().size()
                        + " open finding"
                        + (snapshot.open().size() == 1 ? "" : "s")
                        + " ("
                        + high
                        + " high, "
                        + medium
                        + " medium, "
                        + rest
                        + " low or info); weave review "
                        + "main.loom"
                        + " lists them");
        new java.util.TreeMap<>(snapshot.cost())
                .forEach(
                        (name, t) ->
                                out.add(
                                        name
                                                + ": typical "
                                                + t.typical()
                                                + " tokens, worst case "
                                                + (t.unbounded()
                                                        ? "unbounded"
                                                        : t.worst() + " tokens")
                                                + " per run"));
        return out;
    }
}
