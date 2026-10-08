package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSettings;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;

/** {@code --prompts <dir>} and {@code --prompt id@vN}: where an agent's prompt files are, and which version of one to run. */
final class PromptOptions {

    @Option(names = "--prompts", paramLabel = "<dir>",
            description = "The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).")
    File dir;

    @Option(names = "--prompt", paramLabel = "<id@vN>",
            description = "Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).")
    List<String> pins = new ArrayList<>();

    /** The settings these options give; throws {@link IllegalArgumentException} (with a message to show) for a bad pin. */
    PromptSettings settings() {
        return new PromptSettings(dir == null ? null : dir.toPath(), PromptCatalog.pins(pins));
    }

    /** The environment with these settings, or null (after saying why) when a pin is not valid. */
    WeaveEnv apply(WeaveEnv env) {
        try {
            return env.withPrompts(settings());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return null;
        }
    }
}
