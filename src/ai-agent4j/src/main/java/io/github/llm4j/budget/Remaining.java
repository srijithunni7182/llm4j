package io.github.llm4j.budget;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

/** What is left of each limited dimension; empty means unlimited. */
public record Remaining(
        OptionalLong tokens, OptionalLong calls, Optional<BigDecimal> cost, OptionalLong searches) {

    /** Remaining with no search limit (the shape before grounding existed). */
    public Remaining(OptionalLong tokens, OptionalLong calls, Optional<BigDecimal> cost) {
        this(tokens, calls, cost, OptionalLong.empty());
    }
}
