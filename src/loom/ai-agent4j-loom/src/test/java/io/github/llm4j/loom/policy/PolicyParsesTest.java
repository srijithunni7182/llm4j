package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

class PolicyParsesTest {

    static LoomScript parse(String s) {
        return new LoomParser(new Lexer(s).tokenize()).parseScript();
    }

    @Test
    void aPolicyNamesItsFileAndItsEvaluator() {
        LoomScript s =
                parse(
                        "policy HouseRules: \"house-rules\" checked by Compliance\npolicy Legal: \"legal@v2\"\n");
        assertThat(s.getPolicies()).hasSize(2);
        assertThat(s.getPolicies().get(0).getName()).isEqualTo("HouseRules");
        assertThat(s.getPolicies().get(0).getReference()).isEqualTo("house-rules");
        assertThat(s.getPolicies().get(0).getCheckedBy()).isEqualTo("Compliance");
        assertThat(s.getPolicies().get(1).getReference()).isEqualTo("legal@v2");
        assertThat(s.getPolicies().get(1).getCheckedBy()).isNull();
    }

    @Test
    void policyIsStillAnOrdinaryWordElsewhere() {
        LoomScript s =
                parse(
                        "agent A { model: \"m\" system: \"policy\" }\nworkflow Main(policy) { set policy = \"x\" return policy }\n");
        assertThat(s.getPolicies()).isEmpty();
        assertThat(s.getWorkflows()).hasSize(1);
    }

    @Test
    void anEvaluatorReadsEverySetting() {
        LoomScript s =
                parse(
                        "evaluator Compliance { model: \"claude-haiku-5-5\" unsure below 0.7 samples: 3 pii: mask fallback: [\"a\", \"b\"] timeout: 20s }\n");
        EvaluatorDef e = s.findEvaluator("Compliance");
        assertThat(e.getModel()).isEqualTo("claude-haiku-5-5");
        assertThat(e.getUnsureBelow()).isEqualTo(0.7);
        assertThat(e.isUnsureBelowWritten()).isTrue();
        assertThat(e.getSamples()).isEqualTo(3);
        assertThat(e.getPii()).isEqualTo("mask");
        assertThat(e.getFallback()).containsExactly("a", "b");
        assertThat(e.getTimeoutMillis()).isEqualTo(20_000);
    }

    @Test
    void anEvaluatorHasDefaults() {
        EvaluatorDef e = parse("evaluator C { model: \"m\" }").findEvaluator("C");
        assertThat(e.getUnsureBelow()).isEqualTo(0.8);
        assertThat(e.isUnsureBelowWritten()).isFalse();
        assertThat(e.getSamples()).isEqualTo(1);
    }

    @Test
    void anEvaluatorNeedsAModel() {
        assertThatThrownBy(() -> parse("evaluator C { samples: 3 }")).hasMessageContaining("needs a model");
    }

    @Test
    void anEvaluatorIsStatelessSoToolsMemoryAndInstructionsAreRefused() {
        for (String setting : new String[] {"tools: [A]", "memory: x", "persona: x", "instructions: \"x {y}\"", "max_iterations: 3"}) {
            assertThatThrownBy(() -> parse("evaluator C { model: \"m\" " + setting + " }"))
                    .as(setting)
                    .hasMessageContaining("is not something an evaluator can have");
        }
    }

    @Test
    void anUnknownSettingSuggestsTheNearestOne() {
        assertThatThrownBy(() -> parse("evaluator C { model: \"m\" sample: 3 }"))
                .hasMessageContaining("Did you mean samples");
    }

    @Test
    void unsureBelowNeedsANumberFromZeroToOne() {
        assertThatThrownBy(() -> parse("evaluator C { model: \"m\" unsure below 2 }"))
                .hasMessageContaining("0 to 1");
    }

    @Test
    void mergingKeepsPoliciesAndEvaluators() {
        LoomScript a = parse("policy P: \"p\"\nevaluator E { model: \"m\" }\n");
        LoomScript b = parse("");
        b.merge(a);
        assertThat(b.getPolicies()).hasSize(1);
        assertThat(b.getEvaluators()).hasSize(1);
    }
}
