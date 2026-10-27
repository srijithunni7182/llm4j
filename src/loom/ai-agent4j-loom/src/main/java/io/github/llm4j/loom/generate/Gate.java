package io.github.llm4j.loom.generate;

/** One of the checks a generated project must pass before it is called done. */
public interface Gate {

    String id();

    GateResult run(Workspace workspace, Plan plan);
}
