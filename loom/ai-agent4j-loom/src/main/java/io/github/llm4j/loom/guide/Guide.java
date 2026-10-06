package io.github.llm4j.loom.guide;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The written guide, from inside the jar: the ten chapters, the Loom reference and a short index for tools, and the skill that teaches an agent
 * to use them. They are copied in when the jar is built, so they are there for someone who has the jar and nothing else.
 */
public final class Guide {

    /** One page of the guide: the name to ask for, what it is, and where it is in the jar. */
    public record Page(String name, String title, String resource) {}

    private static final List<Page> PAGES = List.of(
            new Page("readme", "The path, and whether you want tests first", "guide/README.md"),
            new Page("1", "Decide your agents (and: a script, or Java)", "guide/01-decide-your-agents.md"),
            new Page("2", "Build a golden dataset (optional)", "guide/02-golden-dataset.md"),
            new Page("3", "Test the prompts", "guide/03-prompt-tests.md"),
            new Page("4", "Optimize the prompts", "guide/04-prompt-optimization.md"),
            new Page("5", "Test each agent, with spend caps", "guide/05-test-agents-with-caps.md"),
            new Page("6", "Build the workflow", "guide/06-build-the-workflow.md"),
            new Page("7", "Validate and audit", "guide/07-validate-and-audit.md"),
            new Page("8", "Test the trajectory", "guide/08-trajectory-tests.md"),
            new Page("9", "Go live: real APIs, budgets, cost checks", "guide/09-go-live.md"),
            new Page("10", "Best practices and checklist", "guide/10-best-practices.md"),
            new Page("recipes", "Recipes: change the starter to do what you want", "guide/RECIPES.md"),
            new Page("loom", "The Loom reference: every statement, option and command", "guide/LOOM_GUIDE.md"),
            new Page("llms", "A one-page index of the framework, for tools and agents", "guide/llms.txt"));

    private Guide() {}

    public static List<Page> pages() {
        return PAGES;
    }

    /** The page asked for: {@code 6}, {@code 06}, {@code loom}, {@code llms}, {@code readme}, or a chapter's name such as {@code build-the-workflow}. */
    public static Optional<Page> find(String what) {
        String key = what.strip().toLowerCase(Locale.ROOT).replaceFirst("\\.md$", "");
        String number = key.matches("0\\d") ? key.substring(1) : key;
        for (Page p : PAGES) {
            String file = p.resource().substring(p.resource().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT).replaceFirst("\\.(md|txt)$", "");
            String named = file.replaceFirst("^\\d+-", "");
            if (p.name().equals(number) || file.equals(key) || named.equals(key) || (p.name().equals("loom") && key.equals("loom_guide"))) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** The text of a page, or empty when the jar does not have it. */
    public static Optional<String> text(Page page) {
        return read(page.resource());
    }

    /** The skill that teaches an agent to use the guide, with its chapter paths pointing at {@code references/}. */
    public static Optional<String> skill() {
        return read("skill/SKILL.md").map(s -> s.replace("`docs/guide/", "`references/"));
    }

    public static Optional<String> read(String resource) {
        try (InputStream in = Guide.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where the skill goes inside a project. */
    public static Path skillFolder(Path project) {
        return project.resolve(".claude").resolve("skills").resolve("llm4j-workflow-guide");
    }

    /**
     * Writes the skill and the pages it points at into {@code project/.claude/skills/llm4j-workflow-guide} ({@code SKILL.md} and {@code references/}).
     * Without {@code force} nothing is written when the skill is already there.
     *
     * @return the files written, relative to the skill folder; empty when it was already there
     */
    public static List<String> installSkill(Path project, boolean force) throws IOException {
        Path folder = skillFolder(project);
        if (Files.exists(folder.resolve("SKILL.md")) && !force) return List.of();
        String skill = skill().orElseThrow(() -> new IllegalStateException("this jar has no skill (a broken jar?)"));
        List<String> written = new ArrayList<>();
        Files.createDirectories(folder.resolve("references"));
        Files.writeString(folder.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);
        written.add("SKILL.md");
        for (Page p : PAGES) {
            String name = p.resource().substring("guide/".length());
            Files.writeString(folder.resolve("references").resolve(name), text(p).orElseThrow(() -> new IllegalStateException("this jar has no " + p.resource())), StandardCharsets.UTF_8);
            written.add("references/" + name);
        }
        return written;
    }
}
