package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.Scripts2Access;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A trust block checks the same cases blind after it moves to the ladder engine as it did before.
 */
@Tag("L7")
class TrustAuditSampleTest {

    @Test
    void theCasesCheckedBlindAreExactlyTheOnesTheOriginalEngineChecked() {
        String src =
                Scripts2Access.refund(
                        "check 30% of cases with a person who doesn't see the proposal");
        var script = new LoomParser(new Lexer(src).tokenize()).parseScript();
        var decision = script.getDecisions().get(0);
        assertThat(decision.isLadderFromTrust()).isTrue();
        LadderPolicy policy = LadderPolicy.of(script, decision);
        int sampled = 0;
        for (int i = 0; i < 5000; i++) {
            String id = "run-" + i + "/Triage/s0";
            boolean original = Decider.sampledForAudit(id, "Refund", decision.getAuditPercent());
            assertThat(policy.sampled(1, id)).as(id + " at suggest").isEqualTo(original);
            assertThat(policy.sampled(2, id)).as(id + " at act").isEqualTo(original);
            if (original) sampled++;
        }
        assertThat(sampled / 5000.0).isBetween(0.27, 0.33);
        assertThat(policy.sampleBeforeEscalate()).isTrue();
    }
}
