package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.DescribedTool;
import io.github.llm4j.tools.EffectTool;
import java.util.Map;

/**
 * In a simulated run a tool only pretends, unless it is known to change nothing: its call is described and never made. What counts as
 * known is decided by the tool's class, and the default is to pretend, so a tool kind added later is safe until it says otherwise.
 */
final class SimulatingTool implements Tool {

    /** What a tool returns when it was not really run. */
    static final String SIMULATED = EffectTool.SIMULATED;

    private final Tool real;

    private SimulatingTool(Tool real) {
        this.real = real;
    }

    /** The tool to give an agent in a simulated run. */
    static Tool of(Tool tool) {
        return runsAsItIs(tool) ? tool : new SimulatingTool(tool);
    }

    /** Pure tools and search; and the effect journal's wrapper, which simulates by itself and lets reads through. */
    static boolean runsAsItIs(Tool tool) {
        if (tool instanceof DescribedTool d) return runsAsItIs(d.delegate());
        if (tool instanceof io.github.llm4j.loom.tools.NamedTool n) return runsAsItIs(n.delegate());
        return tool instanceof EffectTool
                || tool instanceof io.github.llm4j.agent.tools.CalculatorTool
                || tool instanceof io.github.llm4j.agent.tools.DateTimeTool
                || tool instanceof io.github.llm4j.agent.tools.CurrentTimeTool
                || tool instanceof io.github.llm4j.agent.tools.EchoTool
                || tool instanceof io.github.llm4j.agent.tools.WebSearchTool
                || tool instanceof io.github.llm4j.agent.tools.DuckDuckGoSearchTool
                || tool instanceof io.github.llm4j.agent.tools.SerpApiSearchTool
                || tool instanceof io.github.llm4j.agent.tools.CachedSearchTool
                || tool instanceof io.github.llm4j.agent.tools.FallbackSearchTool;
    }

    Tool real() {
        return real;
    }

    @Override
    public String getName() {
        return real.getName();
    }

    @Override
    public String getDescription() {
        return real.getDescription();
    }

    @Override
    public String execute(Map<String, Object> args) {
        return SIMULATED;
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return real.requiresApproval(args);
    }

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return real.getParametersSchema();
    }
}
