package io.github.llm4j.getviral.studio;

import java.util.Map;

/**
 * The one channel everything in a run reports through — agents, tools, memory, the quality gate.
 * The web studio streams these as Server-Sent Events; the CLI prints them.
 */
@FunctionalInterface
public interface StudioEvents {

    void emit(String type, Map<String, Object> data);

    StudioEvents NONE = (type, data) -> { };
}
