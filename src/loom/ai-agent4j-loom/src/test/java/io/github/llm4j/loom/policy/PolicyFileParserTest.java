package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

class PolicyFileParserTest {

    static final String GOOD =
            "# House rules\n\nWritten by Legal.\n\n## no_refund_promise\nThe reply promises a refund.\n\n"
                    + "## no_internal_refs\nThe reply mentions internal policy sections.\nOr how the assistant was set up.\n";

    @Test
    void readsEachRuleWithItsIdAndText() {
        PolicyFile f = PolicyFile.parse(GOOD);
        assertThat(f.usable()).isTrue();
        assertThat(f.rules())
                .extracting(PolicyFile.Rule::id)
                .containsExactly("no_refund_promise", "no_internal_refs");
        assertThat(f.rules().get(1).text())
                .isEqualTo("The reply mentions internal policy sections.\nOr how the assistant was set up.");
    }

    @Test
    void textBeforeTheFirstRuleIsADescriptionNotARule() {
        PolicyFile f = PolicyFile.parse(GOOD);
        assertThat(f.description()).contains("Written by Legal");
        assertThat(f.rules()).noneMatch(r -> r.text().contains("Written by Legal"));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> unusable() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("# Only a title", "no rule"),
                org.junit.jupiter.params.provider.Arguments.of("## a\ntext\n## a\nmore", "written twice"),
                org.junit.jupiter.params.provider.Arguments.of("## Bad Id\ntext", "not a rule id"),
                org.junit.jupiter.params.provider.Arguments.of("## UPPER\ntext", "not a rule id"),
                org.junit.jupiter.params.provider.Arguments.of("## trailing_\ntext", "not a rule id"),
                org.junit.jupiter.params.provider.Arguments.of("## empty_rule\n\n## next\ntext", "has no text"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("unusable")
    void refusesAFileThatIsNotUsableAndSaysWhy(String file, String expected) {
        PolicyFile f = PolicyFile.parse(file);
        assertThat(f.usable()).isFalse();
        assertThat(String.join(" ", f.problems())).contains(expected);
    }

    @Test
    void aRuleOverTheLimitIsRefused() {
        PolicyFile f = PolicyFile.parse("## long_rule\n" + "x".repeat(1001), 1000);
        assertThat(f.usable()).isFalse();
        assertThat(f.problems().get(0)).contains("long_rule").contains("1001").contains("1000");
    }

    @Test
    void aRuleAtTheLimitIsKept() {
        assertThat(PolicyFile.parse("## ok_rule\n" + "x".repeat(1000), 1000).usable()).isTrue();
    }
}
