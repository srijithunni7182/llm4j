package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R2 of docs/specs/28: a host that knows nothing measures the script says so on the start event. */
class RunStartEvaluatedFlagTest {

    private static List<TraceEvent> trace(Boolean evaluated) {
        LoomScript script = new LoomParser(new Lexer("workflow Main() { note \"hi\" }\n").tokenize()).parseScript();
        List<TraceEvent> events = new ArrayList<>();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), m -> null);
        if (evaluated != null) executor.setEvaluated(evaluated);
        executor.addTraceListener(events::add);
        executor.executeWorkflow("Main", java.util.Map.of());
        return events;
    }

    private static Object flag(List<TraceEvent> events) {
        return events.stream()
                .filter(e -> TraceEvent.RUN_START.equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data()
                .get("evaluated");
    }

    @Test
    void unevaluatedRunsCarryTheFlag() {
        assertThat(flag(trace(false))).isEqualTo("false");
    }

    @Test
    void evaluatedRunsAndHostsThatDidNotSayCarryNothing() {
        assertThat(flag(trace(true))).isNull();
        assertThat(flag(trace(null))).isNull();
    }
}
