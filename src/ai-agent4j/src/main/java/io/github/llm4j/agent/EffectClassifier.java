package io.github.llm4j.agent;

import io.github.llm4j.agent.tool.Effectful;
import java.util.Map;

/**
 * Says whether running a tool with these arguments may change something outside the process. The agent uses it to fill in
 * {@link StopReport#mayHaveChangedThings()}. The default is cautious: an {@link Effectful} tool says for each call, and any other tool is assumed to
 * change something. A host that knows better (for instance, which of its tools only read) supplies its own with
 * {@link ReActAgent.Builder#effectClassifier(EffectClassifier)}.
 */
@FunctionalInterface
public interface EffectClassifier {

    boolean mayChange(Tool tool, Map<String, Object> arguments);

    /** The cautious default. */
    EffectClassifier CAUTIOUS = (tool, args) -> !(tool instanceof Effectful e) || e.isEffect(args == null ? Map.of() : args);
}
