package io.github.llm4j.eval.optimize;

import java.util.List;

/**
 * Whether the optimized prompt can be trusted as a real improvement, with the reasons. {@code
 * generalized} is true only when the sealed test split, the confirmation run and the guardrails all
 * agree.
 */
public record Verdict(boolean generalized, List<String> reasons) {

    public Verdict {
        reasons = List.copyOf(reasons);
    }
}
