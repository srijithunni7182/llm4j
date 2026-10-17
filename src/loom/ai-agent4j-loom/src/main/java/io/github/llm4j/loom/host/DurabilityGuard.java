package io.github.llm4j.loom.host;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LocatedStatement;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;

/**
 * A run that waits for a person, a timer or a limit can only carry on if something remembers it. This finds the places in a script that wait, so a host
 * with no store can refuse the script at start-up instead of losing a run later.
 */
public final class DurabilityGuard {

    private DurabilityGuard() { }

    /** Each construct that can suspend a run, as {@code "human_prompt (line 12)"}. */
    public static List<String> suspending(LoomScript script) {
        List<String> found = new ArrayList<>();
        for (WorkflowDef w : script.getWorkflows()) {
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof HumanPromptStmt) found.add("human_prompt" + line(s));
                if (s instanceof SleepStmt) found.add("sleep" + line(s));
            });
        }
        for (AgentDef a : script.getAgents()) {
            if (a.isApproveAll() || !a.getApprove().isEmpty()) found.add("approve: on agent " + a.getName() + " (line " + a.getLine() + ")");
        }
        if (script.getRateLimits() != null && script.getRateLimits().getOnLimit() == io.github.llm4j.loom.ast.RateLimitDef.OnLimit.SUSPEND) found.add("rate_limits");
        if (script.getBudget() != null && script.getBudget().getWindow() != null) found.add("a windowed budget");
        return found;
    }

    /** Each construct that keeps a record across runs and so is useless without a store: {@code decision} and {@code ladder}. */
    public static List<String> needsStore(LoomScript script) {
        List<String> found = new ArrayList<>();
        script.getDecisions().forEach(d -> found.add("decision " + d.getName() + " (line " + d.getLine() + ")"));
        script.getLadders().forEach(l -> found.add("ladder (line " + l.getLine() + ")"));
        return found;
    }

    /**
     * @param durable whether the host has a store
     * @param allowNonDurable accept constructs that suspend without a store (a suspension then fails the run); never accepts {@link #needsStore}
     * @throws IllegalStateException naming each construct
     */
    public static void check(LoomScript script, boolean durable, boolean allowNonDurable) {
        if (durable) return;
        List<String> store = needsStore(script);
        if (!store.isEmpty()) throw new IllegalStateException("this script keeps earned-autonomy records (" + String.join(", ", store) + ") and needs a store to keep them: give the host a persistence");
        List<String> waits = suspending(script);
        if (!waits.isEmpty() && !allowNonDurable) {
            throw new IllegalStateException("this script can wait for a person, a timer or a limit (" + String.join(", ", waits)
                    + ") and a run that waits needs a store to resume from: give the host a persistence, or allow non-durable runs to fail when they would suspend");
        }
    }

    private static String line(Object s) {
        return s instanceof LocatedStatement l ? " (line " + l.getLine() + ")" : "";
    }
}
