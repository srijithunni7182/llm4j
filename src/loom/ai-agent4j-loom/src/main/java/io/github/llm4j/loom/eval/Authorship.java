package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;

/**
 * Who wrote a case, and whether a person has read it. A case an assistant drafted carries the tag
 * {@code author:assistant}; a person who accepts or edits it adds {@code reviewed_by:<name>}. A
 * case with no {@code author:} tag was written by a person. Tags carry this so the dataset format
 * does not change.
 */
public final class Authorship {
    public static final String ASSISTANT_TAG = "author:assistant";
    public static final String REVIEWED_BY_PREFIX = "reviewed_by:";

    private Authorship() {}

    /** True for a case an assistant drafted that no person has named themselves as reviewing. */
    public static boolean needsReview(EvalScenario s) {
        if (s.tags() == null || !s.tags().contains(ASSISTANT_TAG)) return false;
        return s.tags().stream()
                .noneMatch(
                        t ->
                                t != null
                                        && t.startsWith(REVIEWED_BY_PREFIX)
                                        && !t.substring(REVIEWED_BY_PREFIX.length()).isBlank());
    }

    /** How many of the cases an assistant drafted and a person has not yet reviewed. */
    public static long unreviewed(List<EvalScenario> cases) {
        return cases.stream().filter(Authorship::needsReview).count();
    }
}
