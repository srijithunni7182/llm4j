package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.review.engine.ReviewInput;
import java.util.Optional;

/**
 * The text of an agent's prompt as the review can read it: the inline {@code system:} text, or the
 * prompt file it names when the prompt folder can be found. Empty when the prompt is a file that
 * cannot be read, so rules about prompt text stay unknown rather than guessing.
 */
final class PromptText {

    private final ReviewInput input;
    private PromptCatalog catalog;
    private boolean tried;

    PromptText(ReviewInput input) {
        this.input = input;
    }

    Optional<String> of(AgentDef a) {
        if (a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank())
            return Optional.of(a.getSystemPrompt());
        if (a.getPrompt() == null || a.getPrompt().isBlank()) return Optional.of("");
        PromptCatalog c = catalog();
        if (c == null) return Optional.empty();
        return c.resolve(a.getPrompt()).map(PromptCatalog.Resolved::text);
    }

    private PromptCatalog catalog() {
        if (!tried) {
            tried = true;
            try {
                catalog =
                        PromptSupport.catalog(
                                input.script(), input.baseDir().resolve(input.file()), null);
            } catch (RuntimeException e) {
                catalog = null;
            }
        }
        return catalog;
    }
}
