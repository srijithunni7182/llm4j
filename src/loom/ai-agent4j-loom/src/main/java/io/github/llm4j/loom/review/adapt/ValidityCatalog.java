package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Lens;
import java.util.List;

/**
 * Explains the validity lens: what {@code weave check} reports, until its messages are classified
 * into rules of their own.
 */
public final class ValidityCatalog {

    private ValidityCatalog() {}

    public static List<RuleInfo> rules() {
        return List.of(
                new RuleInfo(
                        ValiditySource.UNCLASSIFIED,
                        Lens.VALIDITY,
                        null,
                        "A problem weave check reports",
                        "weave check found something wrong or doubtful in the script: an unknown name, a missing file or secret, a result nothing uses. An error is a high finding and a warning a low one. Run weave check for the full message.",
                        "validator"));
    }
}
