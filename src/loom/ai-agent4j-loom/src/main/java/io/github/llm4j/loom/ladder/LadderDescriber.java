package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.expr.Expr;
import java.util.ArrayList;
import java.util.List;

/** A ladder in plain sentences, generated from the parsed ladder: what is read is what is enforced. */
public final class LadderDescriber {

    private LadderDescriber() { }

    /** The ladder described; {@code decision} (may be null) says which decision binds it, how high it may climb and with which values. */
    public static String describe(LadderDef ladder, DecisionDef decision) {
        StringBuilder b = new StringBuilder();
        List<RungDef> rungs = ladder.getRungs();
        b.append("Ladder ").append(ladder.getName()).append('\n');
        if (decision != null) {
            String ceiling = decision.getCeilingRung() == null ? rungs.get(0).getName() : decision.getCeilingRung();
            b.append("Used by decision ").append(decision.getName()).append(", which may climb as far as ").append(ceiling).append(".\n");
            decision.getLadderArgs().forEach((k, v) -> b.append("  ").append(k).append(" = ").append(v.show()).append('\n'));
        } else if (!ladder.getParams().isEmpty()) {
            b.append("It takes: ").append(String.join(", ", ladder.getParams())).append(".\n");
        }
        if (!rungs.isEmpty()) b.append("A new scope starts on ").append(rungs.get(0).getName()).append(".\n");
        for (int i = 0; i < rungs.size(); i++) {
            RungDef r = rungs.get(i);
            b.append('\n').append("Rung ").append(r.getName()).append(": ").append(mode(r.getMode())).append('\n');
            if (r.getClimb() != null) {
                b.append("  The agent climbs to ").append(r.getName()).append(" when:\n");
                bullets(b, flatten(r.getClimb(), "and"), ", and");
            }
            if (r.getSlip() != null) {
                String to = r.getSlipTo() != null ? r.getSlipTo() : i > 0 ? rungs.get(i - 1).getName() : "?";
                List<Expr> parts = flatten(r.getSlip(), "or");
                if (parts.size() > 1) {
                    b.append("  The agent slips from ").append(r.getName()).append(" to ").append(to).append(" when any of these holds:\n");
                    bullets(b, parts, ", or");
                } else {
                    b.append("  The agent slips from ").append(r.getName()).append(" to ").append(to).append(" when:\n");
                    bullets(b, parts, "");
                }
            }
            if (r.getSamplePercent() > 0) {
                b.append("  ").append(Show.number(r.getSamplePercent() * 100)).append("% of its cases are sent to a person who does not see the proposal.\n");
            }
            if (r.getEscalate() != null) {
                List<Expr> parts = flatten(r.getEscalate(), "or");
                b.append("  A person is also asked, whatever the rung, when ").append(parts.size() > 1 ? "any of these holds:\n" : ":\n");
                bullets(b, parts, parts.size() > 1 ? ", or" : "");
            }
        }
        b.append('\n').append(ladder.isAutomatic() ? "Moving up is automatic when the conditions hold.\n" : "Moving up needs approval from " + ladder.getApprover() + ".\n");
        return b.toString();
    }

    private static String mode(RungDef.Mode mode) {
        switch (mode) {
            case BLIND: return "a person answers without seeing the agent's proposal. These answers count as evidence.";
            case CONFIRM: return "a person sees the agent's proposal and accepts, edits or rejects it.";
            default: return "the agent's proposal takes effect without asking.";
        }
    }

    private static List<Expr> flatten(Expr e, String connective) {
        List<Expr> out = new ArrayList<>();
        walk(e, connective, out);
        return out;
    }

    private static void walk(Expr e, String connective, List<Expr> out) {
        if (e instanceof Expr.Bin b && b.op().equals(connective)) {
            walk(b.left(), connective, out);
            walk(b.right(), connective, out);
        } else {
            out.add(e);
        }
    }

    private static void bullets(StringBuilder b, List<Expr> parts, String joiner) {
        for (int i = 0; i < parts.size(); i++) {
            b.append("    - ").append(ExprDescriber.describe(parts.get(i))).append(i < parts.size() - 1 ? joiner : "").append('\n');
        }
    }
}
