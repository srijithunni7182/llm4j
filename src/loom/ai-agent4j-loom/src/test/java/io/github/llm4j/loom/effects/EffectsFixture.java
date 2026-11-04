package io.github.llm4j.loom.effects;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.Capabilities;
import java.util.Map;

/** Parses a script of tool declarations and asks the one resolver about each tool. */
final class EffectsFixture {

    private EffectsFixture() {}

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static Map<String, ToolDef> tools(LoomScript script) {
        Map<String, ToolDef> out = new java.util.LinkedHashMap<>();
        for (ToolDef t : script.getTools()) out.put(t.getName(), t);
        return out;
    }

    static Capabilities resolve(String toolDeclaration) {
        LoomScript script = parse(toolDeclaration);
        String name = script.getTools().get(0).getName();
        return Capabilities.resolve(name, tools(script), Map.of());
    }
}
