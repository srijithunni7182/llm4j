package io.github.llm4j.budget;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

/** What is left of each limited dimension; empty means unlimited. */
public record Remaining(OptionalLong tokens, OptionalLong calls, Optional<BigDecimal> cost) { }
