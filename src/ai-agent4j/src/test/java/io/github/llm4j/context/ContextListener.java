package io.github.llm4j.context;

import io.github.llm4j.agent.AgentEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects the context events an agent reports. */
final class ContextListener implements AgentEventListener {
    final List<ContextUsage> usages = Collections.synchronizedList(new ArrayList<>());
    final List<CompactionEvent> compactions = Collections.synchronizedList(new ArrayList<>());

    @Override public void onThought(String thought) {}
    @Override public void onAction(String toolName, String toolInput) {}
    @Override public void onObservation(String observation) {}

    @Override
    public void onContextUsage(ContextUsage usage) {
        usages.add(usage);
    }

    @Override
    public void onCompaction(CompactionEvent event) {
        compactions.add(event);
    }

    long compacted() {
        return compactions.stream().filter(e -> e.result().outcome() == CompactionResult.Outcome.COMPACTED).count();
    }
}
