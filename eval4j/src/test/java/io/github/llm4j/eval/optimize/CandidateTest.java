package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateTest {

    @Test
    void seedHasProvenanceAndImmutableParameters() {
        Candidate seed = Candidate.of("system-prompt", "Be helpful.");

        assertThat(seed.id()).isEqualTo("c0");
        assertThat(seed.parentId()).isNull();
        assertThat(seed.origin()).isEqualTo(Candidate.Origin.SEED);
        assertThat(seed.get("system-prompt")).isEqualTo("Be helpful.");
        assertThat(seed.parameterNames()).containsExactly("system-prompt");
        assertThatThrownBy(() -> seed.parameters().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void derivedChildReplacesOneParameterAndRecordsParent() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("system-prompt", "A");
        params.put("tool-description", "B");
        Candidate seed = Candidate.of(params);

        Candidate child = seed.derive("c1", "tool-description", "B2", 3);

        assertThat(child.parentId()).isEqualTo("c0");
        assertThat(child.origin()).isEqualTo(Candidate.Origin.REWRITE);
        assertThat(child.round()).isEqualTo(3);
        assertThat(child.get("system-prompt")).isEqualTo("A");
        assertThat(child.get("tool-description")).isEqualTo("B2");
        assertThat(child.parameterNames()).containsExactly("system-prompt", "tool-description");
        assertThat(seed.get("tool-description")).isEqualTo("B");
    }

    @Test
    void equalityIsByParameterContentOnly() {
        Candidate a = Candidate.of("p", "same");
        Candidate derivedSame = a.derive("c9", "p", "same", 4);

        assertThat(derivedSame).isEqualTo(a).hasSameHashCodeAs(a);
        assertThat(a).isNotEqualTo(Candidate.of("p", "different"));
        assertThat(a).isNotEqualTo("p");
    }

    @Test
    void totalLengthSumsParameterText() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "12345");
        params.put("b", "123");
        assertThat(Candidate.of(params).totalLength()).isEqualTo(8);
    }

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> Candidate.of(" ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Candidate.of("p", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Candidate.of(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Candidate seed = Candidate.of("p", "x");
        assertThatThrownBy(() -> seed.get("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seed.derive("c1", "nope", "y", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seed.toString()).contains("c0").contains("SEED");
    }
}
