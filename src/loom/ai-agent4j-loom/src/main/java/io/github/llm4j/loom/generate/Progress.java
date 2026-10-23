package io.github.llm4j.loom.generate;

import java.util.Map;

/** Told what a generation is doing, step by step: what an editor shows while it waits. */
@FunctionalInterface
public interface Progress {

    void event(String name, Map<String, Object> data);

    Progress NONE = (name, data) -> {};
}
