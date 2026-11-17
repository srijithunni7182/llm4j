package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.ArrayList;
import java.util.List;

/**
 * What in a script makes it run on its own for a while, and so needs a full harness design rather
 * than a short one: a schedule, supervising other workflows, a loop, or a tool that changes
 * something outside. Read from the script alone; nothing runs.
 */
final class HarnessShape {

    private HarnessShape() {}

    /** The reasons, in plain words; empty for a one-shot workflow with no outside effect. */
    static List<String> reasons(LoomScript script) {
        if (script == null) return List.of();
        List<String> out = new ArrayList<>();
        if (!script.getSchedules().isEmpty()) out.add("it runs on a schedule");
        if (!script.getSupervisions().isEmpty() || StatementWalker.any(script, s -> s instanceof SuperviseStmt))
            out.add("it supervises other workflows");
        if (StatementWalker.any(script, s -> s instanceof LoopStmt)) out.add("it loops");
        List<String> changing = new ArrayList<>();
        for (ToolDef t : script.getTools())
            if (t.getEffect().map(e -> e == TaskEffect.CHANGES).orElse(false)) changing.add(t.getName());
        if (!changing.isEmpty()) out.add("tools change things outside (" + String.join(", ", changing) + ")");
        return List.copyOf(out);
    }

    /** The detail line of the {@code design-harness} stage when it is not done yet. */
    static String advice(LoomScript script) {
        List<String> why = reasons(script);
        return why.isEmpty()
                ? "write HARNESS.md with the harness-design skill; this workflow runs once and changes nothing outside, so a short one is enough"
                : "write HARNESS.md with the harness-design skill; " + String.join(", ", why) + ", so it needs a full one";
    }
}
