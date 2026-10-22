package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.LoomEngine;
import io.github.llm4j.loom.runtime.VariableContext;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LoomEngineCompatTest {

    /**
     * An engine written before invoker credentials: it implements only the four original methods.
     */
    private static final class OldEngine implements LoomEngine {
        final List<String> ran = new ArrayList<>();

        @Override
        public void initialize() {}

        @Override
        public void executeWorkflow(String workflowName, Map<String, String> initialContext) {
            ran.add(workflowName + initialContext);
        }

        @Override
        public void shutdown() {}

        @Override
        public VariableContext getContext() {
            return null;
        }
    }

    @Test
    @Tag("CC-V6.2")
    void anEngineThatKnowsNothingOfInvokersStillCompilesAndRunsTheWorkflow() {
        OldEngine engine = new OldEngine();

        engine.executeWorkflow("Main", Map.of("a", "b"), InvokerCredentials.none());

        assertThat(engine.ran).containsExactly("Main{a=b}");
    }
}
