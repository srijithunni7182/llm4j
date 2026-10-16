package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.tool.EffectPolicy;

/** Settings shared by the factory's tasks. */
final class Tasks {

    /**
     * Every task that writes does so inside one project's folder, and doing it twice gives the same folder, so a resumed run or a
     * rewind may repeat it: the policy says so, which is what lets the workflow's {@code side effects: repeat} pass {@code weave check}.
     */
    static final EffectPolicy REPEATABLE = new EffectPolicy(EffectPolicy.OnUnknown.RETRY, true, 0);

    private Tasks() {}
}
