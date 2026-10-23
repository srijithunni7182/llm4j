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
            String rule, Lens lens, Severity severity, int line, String title, String fixHint) {}

    /** The open findings after the safe fixes, and what the fixes were. */
    public record Reviewed(List<Item> open, List<String> fixed) {}

    /** Reviews the script and applies its SAFE fixes to the file, in place. */
    public interface Reviewer {
        Reviewed reviewAndFix(Path script) throws Exception;
    }

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
