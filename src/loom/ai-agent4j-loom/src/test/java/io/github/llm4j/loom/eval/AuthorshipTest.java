package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorshipTest {

    private static EvalScenario withTags(String... tags) {
        return new EvalScenario(
                "case", "an input", null, null, null, null, null, null, null, List.of(tags), null, null, null);
    }

    @Test
    void aCaseWithNoAuthorTagWasWrittenByAPerson() {
        assertThat(Authorship.needsReview(withTags("split:validation"))).isFalse();
    }

    @Test
    void aCaseAnAssistantDraftedNeedsReviewUntilAPersonNamesThemselves() {
        assertThat(Authorship.needsReview(withTags("author:assistant"))).isTrue();
        assertThat(Authorship.needsReview(withTags("author:assistant", "reviewed_by:Asha")))
                .isFalse();
    }

    @Test
    void anEmptyReviewerNameIsNotAReview() {
        assertThat(Authorship.needsReview(withTags("author:assistant", "reviewed_by:  "))).isTrue();
    }

    @Test
    void theShareCountsOnlyTheCasesNeedingReview() {
        var cases =
                List.of(
                        withTags("author:assistant"),
                        withTags("author:assistant", "reviewed_by:Asha"),
                        withTags(),
                        withTags("author:assistant"));
        assertThat(Authorship.unreviewed(cases)).isEqualTo(2);
    }
}
