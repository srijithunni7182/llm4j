package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import java.util.Map;

/**
 * A tool that can change the world. {@link EffectTool} journals its calls so a resumed run doesn't repeat
 * them. A tool decides per call: an {@code http} GET is not an effect, a POST is.
 */
public interface Effectful extends Tool {

    /** Runs the call; unlike a plain tool it never throws: failures come back as {@code Error:} text. */
    @Override
    String execute(Map<String, Object> args);

    /** Whether this call changes something outside the process. Reads return false and are not journaled. */
    boolean isEffect(Map<String, Object> args);

    EffectPolicy policy();

    /** A short, non-sensitive description of what the call touches (a host, a path), for audit and trace. */
    String target(Map<String, Object> args);

    /**
     * Makes the call.
     *
     * @param idempotencyKey stable across a resume; null when the kind doesn't send one
     */
    Outcome perform(Map<String, Object> args, String idempotencyKey);
}
