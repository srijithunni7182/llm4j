package io.github.llm4j.loom.generic.support;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.tools.ToolFactory;
import io.github.llm4j.tools.EffectContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Builds a tool from the text of its declaration, the way a script would. */
public final class Declared {

    private final ToolFactory factory;
    private final Map<String, String> env;
    private final Path dir;

    public Declared(ToolFactory factory, Map<String, String> env, Path dir) {
        this.factory = factory;
        this.env = env;
        this.dir = dir;
    }

    public Declared(Map<String, String> env, Path dir) {
        this(new ToolFactory(), env, dir);
    }

    public static ToolDef parse(String declaration) {
        return new LoomParser(new Lexer(declaration).tokenize()).parseScript().getTools().get(0);
    }

    /** What is wrong with the declaration at load time (empty when it's fine). */
    public List<String> problems(String declaration) {
        return factory.problems(parse(declaration), env::get, dir);
    }

    /** The tool, as the executor would build it; fails the test if the declaration has problems. */
    public Tool create(String declaration, EffectContext context) throws Exception {
        ToolDef def = parse(declaration);
        List<String> problems = factory.problems(def, env::get, dir);
        if (!problems.isEmpty()) throw new AssertionError("declaration has problems: " + problems);
        return factory.create(def, env::get, dir, context);
    }
}
