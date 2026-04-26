package io.github.llm4j.loom.memory;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.runtime.VariableContext;

/**
 * A basic MemoryEngine that implements a traditional transcript accumulation model.
 * It appends all tasks and responses to a growing transcript.
 */
public class TranscriptAccumulationEngine implements MemoryEngine {
    
    private final StringBuffer transcript = new StringBuffer();

    @Override
    public String assembleContext(AgentDef agentDef, String taskIntent, VariableContext context) {
        if (transcript.length() == 0) {
            return taskIntent;
        }
        
        return "Conversation History:\n" 
                + transcript.toString() 
                + "\n\nCurrent Task:\n" 
                + taskIntent;
    }

    @Override
    public void storeOutcome(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context) {
        transcript.append("Agent [").append(agentDef.getName()).append("] Task: ").append(taskIntent).append("\n");
        transcript.append("Agent [").append(agentDef.getName()).append("] Response: ").append(result.getFinalAnswer()).append("\n\n");
    }
}
