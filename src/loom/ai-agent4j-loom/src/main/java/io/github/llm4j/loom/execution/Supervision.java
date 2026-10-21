package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.telemetry.ToolCallEvent;
import io.github.llm4j.telemetry.ToolOutcome;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The rules of {@code supervise Agent { }} at work. A step that has rules runs its agent under a
 * {@link Run}: the agent's events (an action, a tool result, a model call, how full the context is)
 * are counted on the thread the agent runs on, and when a rule's condition becomes true its actions
 * are carried out on the step's own handle: a steer, a wrap-up or a cancel. Rules are symbolic:
 * every signal is a count of what the agent did, never an opinion of a model, and what a rule does
 * is journaled, traced and audited like what a person does.
 *
 * <p>A rule fires when its condition <em>becomes</em> true; it fires again only if the condition
 * goes false and true again, and never again at all when it is written {@code once}.
 */
final class Supervision {

    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    private Supervision() {}

    /** What a rule's actions are carried out through. */
    interface Actor {
        void steer(String stepId, String agent, String text, String rule);

        void wrapUp(String stepId, String agent, String rule);

        void cancel(String stepId, String agent, StopOutcome outcome, String rule);
    }

    /** Runs {@code work} with {@code run} watching the agent it starts on this thread. */
    static <T> T watching(Run run, Callable<T> work) throws Exception {
        Run before = CURRENT.get();
        CURRENT.set(run);
        try {
            return work.call();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }

    /**
     * The listener that gives a step's {@link Run} the events of the agent running on this thread.
     */
    static AgentEventListener adapter() {
        return new AgentEventListener() {
            @Override
            public void onThought(String thought) {}

            @Override
            public void onAction(String toolName, String toolInput) {
                Run r = CURRENT.get();
                if (r != null) r.action(toolName, toolInput);
            }

            @Override
            public void onObservation(String observation) {}

            @Override
            public void onToolCall(ToolCallEvent event) {
                Run r = CURRENT.get();
                if (r != null) r.toolCall(event);
            }

            @Override
            public void onModelCall(io.github.llm4j.telemetry.ModelCallEvent event) {
                Run r = CURRENT.get();
                if (r != null) r.modelCall();
            }

            @Override
            public void onContextUsage(io.github.llm4j.context.ContextUsage usage) {
                Run r = CURRENT.get();
                if (r != null) r.context(usage);
            }
        };
    }

    /** One step's watching: the counts, and which rules are firing. */
    static final class Run {
        private final SuperviseDef def;
        private final String stepId;
        private final Actor actor;
        private final boolean[] active;
        private final int[] fired;
        private String lastAction;
        private int repeats;
        private int steps;
        private double contextPercent = -1;
        private final Map<String, Integer> failing = new HashMap<>();

        Run(SuperviseDef def, String stepId, Actor actor) {
            this.def = def;
            this.stepId = stepId;
            this.actor = actor;
            this.active = new boolean[def.getRules().size()];
            this.fired = new int[def.getRules().size()];
        }

        void action(String tool, String input) {
            String key = tool + "\u0000" + (input == null ? "" : input);
            repeats = key.equals(lastAction) ? repeats + 1 : 1;
            lastAction = key;
            evaluate(SuperviseDef.Signal.LOOPING, null);
        }

        void toolCall(ToolCallEvent e) {
            boolean failed = e.outcome() != ToolOutcome.EXECUTED || e.softFailure();
            failing.merge(e.tool(), failed ? 1 : 0, (old, one) -> failed ? old + 1 : 0);
            evaluate(SuperviseDef.Signal.TOOL_FAILING, e.tool());
        }

        void modelCall() {
            steps++;
            evaluate(SuperviseDef.Signal.STEPS, null);
        }

        void context(io.github.llm4j.context.ContextUsage usage) {
            usage.fraction().ifPresent(f -> contextPercent = f * 100);
            evaluate(SuperviseDef.Signal.CONTEXT, null);
        }

        private boolean holds(SuperviseDef.Rule r, String tool) {
            return switch (r.getSignal()) {
                case LOOPING -> repeats >= r.getN();
                case TOOL_FAILING -> r.getTool().equals(tool)
                        && failing.getOrDefault(tool, 0) >= r.getN();
                case CONTEXT -> contextPercent > r.getN();
                case STEPS -> steps > r.getN();
            };
        }

        private void evaluate(SuperviseDef.Signal signal, String tool) {
            List<SuperviseDef.Rule> rules = def.getRules();
            for (int i = 0; i < rules.size(); i++) {
                SuperviseDef.Rule r = rules.get(i);
                if (r.getSignal() != signal
                        || (signal == SuperviseDef.Signal.TOOL_FAILING
                                && !r.getTool().equals(tool))) continue;
                boolean now = holds(r, tool);
                boolean edge = now && !active[i];
                active[i] = now;
                if (!edge || (r.isOnce() && fired[i] > 0)) continue;
                fired[i]++;
                for (SuperviseDef.Action a : r.getActions()) {
                    switch (a.kind()) {
                        case STEER -> actor.steer(stepId, def.getAgent(), a.text(), r.describe());
                        case WRAP_UP -> actor.wrapUp(stepId, def.getAgent(), r.describe());
                        case CANCEL -> actor.cancel(
                                stepId,
                                def.getAgent(),
                                a.outcome() == null
                                        ? null
                                        : StopOutcome.valueOf(a.outcome().name()),
                                r.describe());
                    }
                }
            }
        }
    }
}
