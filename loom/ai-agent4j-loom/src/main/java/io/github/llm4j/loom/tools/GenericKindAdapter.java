package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.EffectContext;
import io.github.llm4j.tools.GenericKind;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Lets a script declare a tool from the ai-agent4j-tools library ({@code use: webhook}): the library knows
 * nothing of scripts, so this presents one of its kinds as a {@link ToolKind}.
 */
final class GenericKindAdapter implements ToolKind {

    private final GenericKind kind;

    GenericKindAdapter(GenericKind kind) {
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
