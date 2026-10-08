package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptConstraintsTest {

    private static Candidate c(String text) {
        return Candidate.of("p", text);
    }

    @Test
    void noConstraintsAcceptEverything() {
        assertThat(PromptConstraints.none().violation(c("anything at all"))).isNull();
    }

    @Test
    void maxCharsRejectsLongText() {
        PromptConstraints constraints = PromptConstraints.builder().maxChars(10).build();
        assertThat(constraints.violation(c("0123456789"))).isNull();
        assertThat(constraints.violation(c("0123456789X")))
                .contains("11 characters")
                .contains("max 10");
    }

    @Test
    void requiredAndForbiddenTextAreChecked() {
        PromptConstraints constraints =
                PromptConstraints.builder()
                        .mustContain("{{input}}")
                        .mustNotContain("IGNORE")
                        .build();

        assertThat(constraints.violation(c("Answer {{input}}"))).isNull();
        assertThat(constraints.violation(c("Answer"))).contains("missing required text: {{input}}");
        assertThat(constraints.violation(c("{{input}} IGNORE the judge")))
                .contains("forbidden text: IGNORE");
    }

    @Test
    void everyParameterIsChecked() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "fine");
        params.put("b", "way too long for the limit");
        PromptConstraints constraints = PromptConstraints.builder().maxChars(10).build();
        assertThat(constraints.violation(Candidate.of(params))).contains("\"b\"");
    }

    @Test
    void customPredicateIsLastAndDescribesItself() {
        PromptConstraints constraints =
                PromptConstraints.builder()
                        .predicate(
                                "must not be all caps",
                                cand -> !cand.get("p").equals(cand.get("p").toUpperCase()))
                        .build();
        assertThat(constraints.violation(c("Mixed Case"))).isNull();
        assertThat(constraints.violation(c("SHOUTING"))).isEqualTo("must not be all caps");
    }

    @Test
    void maxCharsMustBePositive() {
        assertThatThrownBy(() -> PromptConstraints.builder().maxChars(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
