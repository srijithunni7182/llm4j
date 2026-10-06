package io.github.llm4j.loom.prompt;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;

/** Builds the prompt catalog for a script from the settings of a command: one place that decides where prompts come from. */
public final class PromptSupport {

    private PromptSupport() {}

    /**
     * The catalog for {@code script} (loaded from {@code scriptFile}): the folder the command line, the script or the convention
     * names, with the command line's pins. Null when there is no folder at all, so a script that uses {@code prompt:} then gets the
     * "needs prompt files" error rather than an empty prompt.
     */
    public static PromptCatalog catalog(LoomScript script, Path scriptFile, PromptSettings settings) {
        PromptSettings s = settings == null ? PromptSettings.NONE : settings;
        PromptFolderResolver.Location where = PromptFolderResolver.resolve(script, scriptFile, s.dir());
        if (where == null) return null;
        return new PromptCatalog(new MarkdownFolderPromptRegistry(where.dir()), s.pins());
    }
}
