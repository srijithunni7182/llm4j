package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.util.ArrayList;
import java.util.List;

/**
 * Explains the validity lens: what {@code weave check} reports, until its messages are classified
 * into rules of their own.
 */
public final class ValidityCatalog {

    private ValidityCatalog() {}

    public static List<RuleInfo> rules() {
        List<RuleInfo> all = new ArrayList<>();
        for (ValidityClass c : ValidityClass.values())
            all.add(
                    new RuleInfo(
                            c.id(),
                            Lens.VALIDITY,
                            null,
                            c.title(),
                            c.why()
                                    + " An error from weave check is a high finding and a warning a low one. What to do: "
                                    + c.fixHint(),
                            "validator"));
        all.add(
                new RuleInfo(
                        "LV08",
                        Lens.VALIDITY,
                        Severity.MEDIUM,
                        "A suppression has no reason",
                        "A line such as LR05.suppress = Router in review.properties sets a finding aside but gives no reason, so it hides nothing and is reported itself. A suppression nobody explained cannot be reviewed later. What to do: add the reason in quotes after the place.",
                        "review.properties"));
        all.add(
                new RuleInfo(
                        ValiditySource.UNCLASSIFIED,
                        Lens.VALIDITY,
                        null,
                        "A problem weave check reports that has no rule of its own yet",
                        "weave check found something wrong or doubtful in the script that is not one of the classes above. An error is a high finding and a warning a low one. Run weave check for the full message.",
                        "validator"));
        return all;
    }
}
