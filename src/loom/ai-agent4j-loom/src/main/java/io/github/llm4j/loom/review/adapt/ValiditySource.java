package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ValidityProblem;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.List;
import java.util.Optional;

/**
 * Brings what {@code weave check} reports into a review as findings of the validity lens. An error
 * is a high finding and a warning a low one. The problem is given the rule id of its class ({@link
 * ValidityClass}), or {@code LV00} when no class claims it.
 */
public final class ValiditySource implements FindingSource {

    /** The id given to a problem no rule has claimed yet. */
    public static final String UNCLASSIFIED = "LV00";

    @Override
    public String name() {
        return "validator";
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        for (ValidityProblem p : input.problems()) sink.finding(convert(p, input.file()));
    }

    static Finding convert(ValidityProblem p, String file) {
        String subject =
                p.construct() == null || p.construct().isBlank() ? "" : p.construct() + ": ";
        ValidityClass kind = ValidityClass.of(p.message());
        String why =
                p.error()
                        ? "The script does not pass weave check, so it will not run as written."
                        : "weave check accepts the script but warns about this.";
        return new Finding(
                kind == null ? UNCLASSIFIED : kind.id(),
                Lens.VALIDITY,
                p.error() ? Severity.HIGH : Severity.LOW,
                Location.at(file, p.line()),
                subject + p.message(),
                kind == null ? why : kind.why() + " " + why,
                kind == null
                        ? "Fix what the message describes, then run weave check again."
                        : kind.fixHint(),
                List.of(),
                Optional.empty(),
                List.of(),
                "validator");
    }
}
