package io.github.llm4j.loom.prompt;

import java.nio.file.Path;
import java.util.Map;

/** What the command line said about prompts: a folder ({@code --prompts}) and versions pinned for this run ({@code --prompt id@vN}). */
public record PromptSettings(Path dir, Map<String, String> pins) {

    public static final PromptSettings NONE = new PromptSettings(null, Map.of());

    public PromptSettings {
        pins = pins == null ? Map.of() : Map.copyOf(pins);
    }

    public boolean isEmpty() {
        return dir == null && pins.isEmpty();
    }
}
