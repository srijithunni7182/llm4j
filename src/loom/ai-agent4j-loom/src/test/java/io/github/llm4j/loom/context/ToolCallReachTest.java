package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R29: a tool call says which legs of the trifecta the tool touches, and whether that is known or assumed. */
class ToolCallReachTest {

    @TempDir
    Path dir;

    private static List<TraceEvent> calls(TestLoom l) {
        synchronized (l.trace) {
            return l.trace.stream().filter(e -> e.type().equals(TraceEvent.TOOL_CALL)).toList();
        }
    }

    @Test
    void aToolTheRunCannotSeeIntoIsAssumedToReachEverything() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 2);
        l.run();
        assertThat(calls(l)).hasSize(2).allSatisfy(e -> {
            Map<String, Object> d = e.data();
            assertThat(d.get("reach")).isEqualTo("outward,private,untrusted");
            assertThat(d.get("reach_known")).isEqualTo(false);
        });
    }

    @Test
    void aReachDeclaredForTheToolIsUsedAndKnown() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 2);
        l.executor.setToolReach(Map.of("lookup", "reads"));
        l.run();
        assertThat(calls(l)).allSatisfy(e -> {
            assertThat(e.data().get("reach")).isEqualTo("private");
            assertThat(e.data().get("reach_known")).isEqualTo(true);
        });
    }

    @Test
    void aToolWithNoReachAtAllHasAnEmptyReachButIsKnown() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 1);
        l.executor.setToolReach(Map.of("lookup", "none"));
        l.run();
        assertThat(calls(l).get(0).data().get("reach")).isEqualTo("");
        assertThat(calls(l).get(0).data().get("reach_known")).isEqualTo(true);
    }
}
