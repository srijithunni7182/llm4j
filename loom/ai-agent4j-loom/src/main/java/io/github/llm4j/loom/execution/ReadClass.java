package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.tools.DescribedTool;
import io.github.llm4j.tools.EffectTool;
import java.util.Map;

/**
 * Whether a tool call only reads. Decided by the tool's class, never by what the model says about it: an effectful tool says for each call
 * whether it is an effect, the plain built-ins that only compute or look things up are known, and everything else is assumed to change something.
 */
final class ReadClass {

    private ReadClass() { }

    /** The tool under the wrappers that rename, describe, journal or gate it. */
    static Tool unwrap(Tool tool) {
        Tool t = tool;
        for (int i = 0; i < 8; i++) {
            if (t instanceof DescribedTool d) t = d.delegate();
            else if (t instanceof io.github.llm4j.loom.tools.NamedTool n) t = n.delegate();
            else if (t instanceof io.github.llm4j.loom.tools.ApprovalTool a) t = a.delegate();
            else if (t instanceof EvidenceTool e) t = e.inner();
            else if (t instanceof SimulatingTool sim) t = sim.real();
            else if (t instanceof RecordingTool rec) t = rec.real();
            else return t;
        }
        return t;
    }

    static boolean isRead(Tool tool, Map<String, Object> args) {
        Tool t = tool;
        for (int i = 0; i < 8; i++) {
            if (t instanceof EffectTool e) return !e.effectful().isEffect(args == null ? Map.of() : args);
            if (t instanceof Effectful f) return !f.isEffect(args == null ? Map.of() : args);
            Tool next = unwrap(t);
            if (next == t) break;
            t = next;
        }
        return t instanceof io.github.llm4j.agent.tools.CalculatorTool
                || t instanceof io.github.llm4j.agent.tools.DateTimeTool
                || t instanceof io.github.llm4j.agent.tools.CurrentTimeTool
                || t instanceof io.github.llm4j.agent.tools.EchoTool
                || t instanceof io.github.llm4j.agent.tools.WebSearchTool
                || t instanceof io.github.llm4j.agent.tools.DuckDuckGoSearchTool
                || t instanceof io.github.llm4j.agent.tools.SerpApiSearchTool
                || t instanceof io.github.llm4j.agent.tools.CachedSearchTool
                || t instanceof io.github.llm4j.agent.tools.FallbackSearchTool;
    }

    /** Pure tools that compute from their arguments alone: they run in a replay as they are. */
    static boolean isPure(Tool tool) {
        Tool t = unwrap(tool);
        return t instanceof io.github.llm4j.agent.tools.CalculatorTool || t instanceof io.github.llm4j.agent.tools.DateTimeTool || t instanceof io.github.llm4j.agent.tools.EchoTool;
    }

    static boolean isClock(Tool tool) {
        Tool t = unwrap(tool);
        return t instanceof io.github.llm4j.agent.tools.CurrentTimeTool;
    }
}
