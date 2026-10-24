package io.github.llm4j.spring.scan;

import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.spring.LoomWorkflows;

/** A workflow interface found by scanning the application's package. */
@LoomWorkflows
public interface Greetings {
    RunOutcome main(String name);

    RunOutcome other();
}
