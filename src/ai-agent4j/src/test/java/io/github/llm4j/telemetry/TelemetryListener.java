package io.github.llm4j.telemetry;

import io.github.llm4j.agent.AgentEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects the telemetry events an agent reports. */
final class TelemetryListener implements AgentEventListener {
    final List<ToolCallEvent> tools = Collections.synchronizedList(new ArrayList<>());
    final List<ModelCallEvent> models = Collections.synchronizedList(new ArrayList<>());

    @Override public void onThought(String thought) {}
    @Override public void onAction(String toolName, String toolInput) {}
    @Override public void onObservation(String observation) {}

    @Override
    public void onToolCall(ToolCallEvent event) {
        tools.add(event);
    }

    @Override
    public void onModelCall(ModelCallEvent event) {
        models.add(event);
    }

    ToolCallEvent onlyTool() {
        if (tools.size() != 1) throw new AssertionError("expected one tool event, got " + tools.size() + ": " + tools);
        return tools.get(0);
    }
}
