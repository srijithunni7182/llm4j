package io.github.llm4j.tools.docs.example;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.Options;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** {@code tool Count { use: wordcount  max_chars: 5000 }}: counts words, lines and characters. */
public final class WordCountKind extends GenericKind {

    static final int DEFAULT_MAX_CHARS = 10_000;
    static final int MAX_CHARS_CEILING = 100_000;

    @Override
    public String name() {
        return "wordcount";
    }

    @Override
    public Set<String> optional() {
        return Set.of("max_chars");
    }

    @Override
    protected void validate(Options options, Path baseDir) {
        options.integer("max_chars", DEFAULT_MAX_CHARS, 1, MAX_CHARS_CEILING);
    }

    @Override
    protected Tool build(String name, Options options, Path baseDir, EffectContext context) {
        int maxChars = options.integer("max_chars", DEFAULT_MAX_CHARS, 1, MAX_CHARS_CEILING);
        return new WordCountTool(name, maxChars, context);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.pure("counts the words of text it is given");
    }
}
