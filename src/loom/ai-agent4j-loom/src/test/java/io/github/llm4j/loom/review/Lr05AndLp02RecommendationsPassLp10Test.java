package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.rule.RuleDoc;
import org.junit.jupiter.api.Test;

/**
 * Phase 25, R2: LR05 and LP02 may not push a developer into the trap LP10 catches. Each states the
 * way out in its advice (a classifier, an extractor, or a small flat schema), and the small schema
 * it recommends is checked against LP10.
 */
class Lr05AndLp02RecommendationsPassLp10Test extends Lp10Test {

    private static RuleDoc rule(String id) {
        return Reviews.corePack().rules().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void lr05OffersAClassifierAnExtractorAndASmallSchemaInThatOrder() {
        String advice = rule("LR05").fixHint();

        assertThat(advice).contains("intentclassifier").contains("extractor").contains("output_schema");
        assertThat(advice.indexOf("intentclassifier")).isLessThan(advice.indexOf("extractor"));
        assertThat(advice.indexOf("extractor")).isLessThan(advice.indexOf("output_schema"));
    }

    @Test
    void lp02OffersTheSameThreeThenTheSingleWord() {
        String advice = rule("LP02").fixHint();

        assertThat(advice).contains("intentclassifier").contains("extractor").contains("output_schema");
        assertThat(advice.indexOf("output_schema")).isLessThan(advice.indexOf("one of the allowed words"));
    }

    @Test
    void theSmallSchemaLr05RecommendsIsNotFlaggedByLp10EvenForAnAgentWithTools() throws Exception {
        // LR05's fixture branches on r.score; the recommended fix adds an output_schema with that field
        String fixed =
                "tool Files { use: file  root: \"data\"  mode: read }\n"
                        + "agent A { model: \"m\"  system: \"s\"  tools: [Files]  output_schema: { score: number } }\n"
                        + "workflow Main(q) { delegate \"{q}\" to A -> r  alt (r.score < 5) { note \"low\" } else { note \"ok\" } }\n";

        assertThat(review(fixed)).noneMatch(f -> f.rule().equals("LP10")).noneMatch(f -> f.rule().equals("LR05"));
    }

    @Test
    void theSingleWordLp02RecommendsNeedsNoSchemaAtAll() throws Exception {
        String fixed =
                "agent Judge { model: \"m\"  system: \"You decide whether the text is fine. Answer with exactly one of the allowed words: yes or no.\" }\n"
                        + "workflow Main(topic) {\n  delegate \"Is this fine? {topic}\" to Judge -> verdict\n  alt (verdict == \"yes\") { note \"fine\" } else { note \"not fine\" }\n}\n";

        assertThat(review(fixed)).noneMatch(f -> f.rule().equals("LP10")).noneMatch(f -> f.rule().equals("LP02"));
    }
}
