package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.rule.RuleDoc;
import java.util.List;

/**
 * A loaded rule pack: its manifest, its rules (each marked with the pack) and the files its
 * examples are read from.
 */
public record Pack(PackManifest manifest, List<RuleDoc> rules, PackFiles files) {

    public Pack {
        rules = List.copyOf(rules);
    }

    /** {@code name@version}. */
    public String label() {
        return manifest.label();
    }
}
