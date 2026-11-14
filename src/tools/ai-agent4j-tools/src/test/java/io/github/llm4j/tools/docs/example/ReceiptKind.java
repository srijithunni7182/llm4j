package io.github.llm4j.tools.docs.example;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SafePaths;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** {@code tool Receipts { use: receipt  dir: "receipts" }}: appends a line to a log file. */
public final class ReceiptKind extends GenericKind {

    @Override
    public String name() {
        return "receipt";
    }

    @Override
    public Set<String> required() {
        return Set.of("dir");
    }

    @Override
    protected void validate(Options options, Path baseDir) {
        try {
            SafePaths.inside(baseDir, options.require("dir"));
        } catch (IllegalArgumentException e) {
            throw new OptionException("dir: " + e.getMessage());
        }
    }

    @Override
    protected Tool build(String name, Options options, Path baseDir, EffectContext context) {
        return new ReceiptTool(name, SafePaths.inside(baseDir, options.require("dir")), context);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.of(false, true, false, true, "appends to a log file in " + options.get("dir"))
                .journaled(true);
    }
}
