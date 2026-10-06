package io.github.llm4j.loom.prompt;

import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides where a script's prompt files are: the command line first ({@code --prompts}), then the script's own
 * {@code prompts: "./dir"} declaration (relative to the script), then a {@code prompts/} folder next to the script if one exists.
 */
public final class PromptFolderResolver {

    /** Where the prompts are and how that was decided. */
    public record Location(Path dir, Source source) {}

    public enum Source { COMMAND_LINE, SCRIPT, CONVENTION }

    private PromptFolderResolver() {}

    /** The folder to use, or null when none was asked for and there is no {@code prompts/} beside the script. */
    public static Location resolve(LoomScript script, Path scriptFile, Path commandLineDir) {
        Path scriptDir = scriptFile == null || scriptFile.toAbsolutePath().getParent() == null
                ? Path.of("").toAbsolutePath() : scriptFile.toAbsolutePath().getParent();
        if (commandLineDir != null) {
            return new Location(commandLineDir.toAbsolutePath().normalize(), Source.COMMAND_LINE);
        }
        if (script != null && script.getPromptsDir() != null) {
            return new Location(scriptDir.resolve(script.getPromptsDir()).normalize(), Source.SCRIPT);
        }
        Path convention = scriptDir.resolve("prompts");
        return Files.isDirectory(convention) ? new Location(convention.normalize(), Source.CONVENTION) : null;
    }
}
