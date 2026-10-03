package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import org.junit.jupiter.api.Test;

class CaseKeyTest {

    @Test
    void matchesTheFormatSpecTestVectors() {
        assertThat(CaseKey.caseId("refund-inside-window")).isEqualTo("c_36002d9a396666c3");
        assertThat(CaseKey.key("refund-inside-window", "answer-correctness", 0)).isEqualTo("k_e80a100a2103750c");
        assertThat(CaseKey.key("refund-inside-window", "faithfulness", 0)).isEqualTo("k_aba47f539957399a");
        assertThat(CaseKey.caseId("shipping-canada")).isEqualTo("c_548260ae655b164e");
        assertThat(CaseKey.key("shipping-canada", "latency-p95", 0)).isEqualTo("k_d3e3b60569e2483d");
        assertThat(CaseKey.caseId("com.acme.SupportBotEvalTest#cancel[2]")).isEqualTo("c_6b90f524197dda48");
        assertThat(CaseKey.key("com.acme.SupportBotEvalTest#cancel[2]", "tool-order", 1)).isEqualTo("k_51bd8cc9dbefb14a");
    }

    @Test
    void scenarioKeyPrefersIdThenNameThenInputHash() {
        EvalScenario withId = new EvalScenario("n", "i", null, null, null, null, null, "sid", null, null);
        assertThat(CaseKey.of(withId)).isEqualTo("sid");
        assertThat(CaseKey.of(new EvalScenario("n", "i", null, null, null, null, null))).isEqualTo("n");
        assertThat(CaseKey.of(new EvalScenario(null, "hello", null, null, null, null, null))).hasSize(12);
    }

    @Test
    void metricSlugs() {
        assertThat(MetricRef.slug("Answer Correctness")).isEqualTo("answer-correctness");
        assertThat(MetricRef.slug("  ")).isEqualTo("metric");
        assertThat(MetricRef.of("Faithfulness").dimension()).isEqualTo("grounding");
        assertThat(MetricRef.of("My custom").dimension()).isNull();
    }
}
