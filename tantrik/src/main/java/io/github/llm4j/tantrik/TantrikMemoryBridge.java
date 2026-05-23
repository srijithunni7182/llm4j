package io.github.llm4j.tantrik;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.runtime.VariableContext;

public class TantrikMemoryBridge {
    private final MemoryEngine memoryEngine;

    public TantrikMemoryBridge(MemoryEngine memoryEngine) {
        this.memoryEngine = memoryEngine;
    }

    public String recallAndInject(AgentDef agentDef, String taskIntent, VariableContext context, String assembledContext) {
        String source = memoryEngine instanceof EngramEngine ? "Engram" : memoryEngine.getClass().getSimpleName();
        return "## Tantrik Recall Phase (" + source + ")\n" + assembledContext + "\n\n## Tantrik Injection Phase\nTask Intent:\n" + taskIntent;
    }

    public void consolidate(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context) {
        memoryEngine.storeOutcome(agentDef, taskIntent, result, context);
        context.setVariable("_tantrik_last_consolidated_agent", agentDef.getName());
    }
}
