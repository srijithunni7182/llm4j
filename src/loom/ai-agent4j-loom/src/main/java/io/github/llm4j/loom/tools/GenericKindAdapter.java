package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Lets a script declare a tool from ai-agent4j-tools ({@code use: webhook}): the library's kinds implement
 * ai-agent4j's script-agnostic kind contract, and this presents one of them as a Loom {@link ToolKind}.
 */
final class GenericKindAdapter implements ToolKind {

    private final io.github.llm4j.agent.tool.ToolKind kind;

    GenericKindAdapter(io.github.llm4j.agent.tool.ToolKind kind) {
        this.kind = kind;
    }

    @Override public String name() { return kind.name(); }
    @Override public Set<String> required() { return kind.required(); }
    @Override public Set<String> optional() { return kind.optional(); }
    @Override public Set<String> prefixes() { return kind.prefixes(); }
    @Override public Set<String> secrets() { return kind.secrets(); }

    @Override
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        return kind.agentProblem(options, toolName, agentName, approved);
    }

    @Override
    public String check(Map<String, String> options, Path baseDir) {
        return kind.check(options, baseDir);
    }

    @Override
    public Tool create(String name, Map<String, String> options, Path baseDir) throws Exception {
        return kind.create(name, options, baseDir);
    }

    @Override
    public Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) throws Exception {
        return kind.create(name, options, baseDir, context);
    }
}
