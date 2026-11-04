package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.export.WorkflowTrace.Event;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChecksPolicyAssertionTest {

    @TempDir Path dir;

    @BeforeEach
    void setUp() {
        System.setProperty(ExportConfig.DIR, dir.toString());
        System.setProperty(ExportConfig.RUN_ID, "r1");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.TaskTest", "refund");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    private static Event check(List<String> violated, String action) {
        return new Event(
                1.0,
                "policy_check",
                null,
                "Main/s1",
                null,
                "",
                Map.of(
                        "policies", List.of(Map.of("name", "HouseRules", "version", "v1", "hash", "ab")),
                        "violated", violated,
                        "action", action));
    }

    private static WorkflowTrace trace(Event... events) {
        return new WorkflowTrace("wf", List.of(), List.of(), List.of(), List.of(), List.of(events), List.of(), null, 0, null);
    }

    @Test
    void countsChecksRulesAndActionsFromTheTraceWithoutAnyText() {
        WorkflowTrace t = trace(check(List.of("no_promise"), "rewritten"), check(List.of(), "none"));
        assertThat(t.checks("HouseRules")).isEqualTo(2);
        assertThat(t.checks("Other")).isZero();
        assertThat(t.ruleViolated("no_promise")).isTrue();
        assertThat(t.ruleViolated("other")).isFalse();
        assertThat(t.checkActions()).containsExactly("rewritten", "none");
        WorkflowAssertions.assertThat(t).checksPolicy("HouseRules").violated("no_promise").actionTaken("rewritten");
    }

    @Test
    void failsWhenThereWasNoSuchCheckRuleOrAction() {
        WorkflowTrace t = trace(check(List.of(), "none"));
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(t).checksPolicy("Legal")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(t).violated("x")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(t).actionTaken("replaced")).isInstanceOf(AssertionError.class);
    }
}
