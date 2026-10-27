package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;

/**
 * What is known about a rule independent of any script: enough to explain it. {@code severity} is
 * the rule's usual severity, or {@code null} when it varies with the situation.
 *
 * @param id the stable rule id
 * @param lens the lens it belongs to
 * @param severity its default severity, or {@code null} when it varies
 * @param title one line: what the rule looks for
 * @param explain a few sentences: what it checks, why it matters and how to resolve it
 * @param source where the rule comes from: {@code audit}, {@code validator}, {@code pack
 *     name@version}
 */
public record RuleInfo(
        String id, Lens lens, Severity severity, String title, String explain, String source) {}
