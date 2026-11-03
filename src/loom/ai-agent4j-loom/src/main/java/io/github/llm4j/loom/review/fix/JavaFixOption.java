package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.model.FixOption;

/**
 * A way to resolve a finding that is too structural to write as data (several places in the script
 * change together). It either offers an option, or says why it cannot; a fix is never guessed.
 */
public interface JavaFixOption {

    /** What the option offers: the option, or the reason there is none. */
    record Offer(FixOption option, String reason) {
        public static Offer of(FixOption option) {
            return new Offer(option, null);
        }

        public static Offer refused(String reason) {
            return new Offer(null, reason);
        }
    }

    /** The rule whose findings this option belongs to. */
    String ruleId();

    /**
     * The option for the finding on {@code entity}.
     *
     * @param script the parsed script
     * @param source the script's text
     * @param file the script's file name, for the edits
     */
    Offer offer(Entity entity, LoomScript script, SourceText source, String file);
}
