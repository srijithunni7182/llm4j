package io.github.llm4j.tools.docs.example;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import io.github.llm4j.tools.UnknownOutcomeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Appends the {@code line} argument to {@code receipts.log}. A change, so it is journaled. */
final class ReceiptTool extends GenericTool {

    private static final int MAX_LINE = 500;

    private final Path dir;

    ReceiptTool(String name, Path dir, EffectContext context) {
        super(
                name,
                "receipt",
                "Records one line in the receipts log. Give line (one line of text).",
                Redactor.NONE,
                context);
        this.dir = dir;
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return true;
    }

    @Override
    public String target(Map<String, Object> args) {
        return "receipts.log";
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String line = text(args, "line");
        if (line.length() > MAX_LINE || line.contains("\n") || line.contains("\r")) {
            throw new ToolRefusal("line must be one line of at most " + MAX_LINE + " characters");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("receipts.log"),
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The write may or may not have happened: say so, so a resume is not guessed.
            throw new UnknownOutcomeException("could not tell whether the line was written", e);
        }
        return "Recorded.";
    }
}
