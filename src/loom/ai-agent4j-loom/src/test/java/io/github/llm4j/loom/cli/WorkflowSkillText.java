package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.guide.Guide;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The text a coding agent reads from the workflow-guide skill: its {@code SKILL.md} and the full
 * rules it points at in {@code references/guiding-rules.md}. Tests that pin a phrase of the skill
 * read both, because the short form and the full form together are the skill.
 */
final class WorkflowSkillText {

    private static final Path FOLDER = Path.of("../../../.claude/skills/llm4j-workflow-guide");

    private WorkflowSkillText() {}

    /** The repository copy: SKILL.md followed by the guiding rules. */
    static String repository() {
        try {
            return Files.readString(FOLDER.resolve("SKILL.md"))
                    + "\n"
                    + Files.readString(FOLDER.resolve("references/guiding-rules.md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The copy inside the jar: the skill as {@link Guide#skill()} serves it, and the rules. */
    static String jar() {
        return Guide.skill().orElseThrow()
                + "\n"
                + Guide.read("skill/references/guiding-rules.md").orElseThrow();
    }

    /** An installed copy under {@code folder}. */
    static String installed(Path folder) throws IOException {
        return Files.readString(folder.resolve("SKILL.md"))
                + "\n"
                + Files.readString(folder.resolve("references").resolve("guiding-rules.md"));
    }
}
