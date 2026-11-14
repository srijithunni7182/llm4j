package io.github.llm4j.tools.docs.example;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import java.util.Map;

/** Counts the words, lines and characters of the {@code text} argument. */
final class WordCountTool extends GenericTool {

    private final int maxChars;

    WordCountTool(String name, int maxChars, EffectContext context) {
        super(
                name,
                "wordcount",
                "Counts the words, lines and characters of text. Give text.",
                Redactor.NONE,
                context);
        this.maxChars = maxChars;
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String text = text(args, "text");
        if (text.length() > maxChars) {
            throw new ToolRefusal(
                    "text is " + text.length() + " characters; the most allowed is " + maxChars);
        }
        int words = text.strip().split("\\s+").length;
        int lines = text.split("\r?\n", -1).length;
        return "{\"words\":" + words + ",\"lines\":" + lines + ",\"characters\":" + text.length() + "}";
    }
}
