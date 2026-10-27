package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * G2: after the review's SAFE fixes have been applied by the tool (not asked of the model), no HIGH
 * finding is left, and no finding at or above the soft level in the lenses that make a generated
 * workflow tidy (validity, reliability, prompts). What reviews and fixes is behind {@link
 * Reviewer}.
 */
public final class ReviewGate implements Gate {

    /** A finding that is still open, reduced to what the gate and the model need. */
    public record Item(
            String rule,
            Lens lens,
            Severity severity,
            int line,
            String title,
            String why,
            String fixHint) {
        public Item(
                String rule, Lens lens, Severity severity, int line, String title, String fixHint) {
            this(rule, lens, severity, line, title, "", fixHint);
        }
    }

    /** The open findings after the safe fixes, and what the fixes were. */
    public record Reviewed(List<Item> open, List<String> fixed) {}

    /** Reviews the script and applies its SAFE fixes to the file, in place. */
    public interface Reviewer {
        Reviewed reviewAndFix(Path script) throws Exception;
    }

    /**
     * How to fix the findings a model finds hardest, in the terms of this language; added to what
     * the review says when the model is shown the finding.
     */
    static final java.util.Map<String, String> RECIPES =
            java.util.Map.of(
                    "LA01",
                    "Recipe: give the agent that reads outside text no tools and have it answer only typed fields (delegate ... expecting { field: string }); give the tool to a second agent whose task text holds only those typed fields, never the raw text; put approve: [Tool] on the second agent if the tool changes something.",
                    "LP07",
                    "Recipe: rename the workflow parameter or variable to a long name that is not a word of any prompt or delegate text (for example ticket_body), everywhere it is used.");

    static final Set<Lens> SOFT_LENSES = Set.of(Lens.VALIDITY, Lens.RELIABILITY, Lens.PROMPTS);

    private final Reviewer reviewer;
    private final Severity soft;

    /**
     * @param soft findings in the soft lenses at or above this fail the gate (default MEDIUM)
     */
    public ReviewGate(Reviewer reviewer, Severity soft) {
        this.reviewer = reviewer;
        this.soft = soft;
    }

    @Override
    public String id() {
        return "G2";
    }

    @Override
    public GateResult run(Workspace ws, Plan plan) {
        try {
            Reviewed r = reviewer.reviewAndFix(ws.script());
            List<GateResult.Problem> out = new ArrayList<>();
            for (Item i : r.open()) {
                boolean hard = i.severity() == Severity.HIGH;
                boolean tidy = SOFT_LENSES.contains(i.lens()) && i.severity().atLeast(soft);
                if (hard || tidy)
                    out.add(
                            new GateResult.Problem(
                                    ws.script().getFileName().toString(),
                                    i.line(),
                                    i.rule(),
                                    i.title()
                                            // what makes it a problem, so a repair can aim at it
                                            + (hard && i.why() != null && !i.why().isBlank()
                                                    ? " (" + i.why() + ")"
                                                    : "")
                                            + (RECIPES.containsKey(i.rule())
                                                    ? " " + RECIPES.get(i.rule())
                                                    : "")
                                            + (i.fixHint() == null || i.fixHint().isBlank()
                                                    ? ""
                                                    : " — " + i.fixHint())));
            }
            return new GateResult("G2", "review", out);
        } catch (Exception e) {
            return new GateResult(
                    "G2",
                    "review",
                    List.of(
                            new GateResult.Problem(
                                    "main.loom",
                                    0,
                                    "",
                                    "The review could not run: " + e.getMessage())));
        }
    }
}
