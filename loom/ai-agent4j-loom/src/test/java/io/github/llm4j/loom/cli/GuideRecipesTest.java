package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recipes page says "a test applies every recipe to a fresh starter". This is that test: the lines a recipe says to find are really in the
 * starter, replacing them gives a script that passes the strict check and draws a graph, and every recipe has the sentence for an agent.
 */
class GuideRecipesTest {

    private static final Pattern BLOCK = Pattern.compile("```(\\w+) recipe=(\\S+) (find|replace|file=\\S+)\\n(.*?)```", Pattern.DOTALL);

    private record Edit(String find, String replace, Map<String, String> files) {}

    private static Map<String, List<String[]>> blocks() throws Exception {
        String page = Files.readString(Path.of("../../docs/guide/RECIPES.md"));
        Map<String, List<String[]>> byRecipe = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(page);
        while (m.find()) byRecipe.computeIfAbsent(m.group(2), k -> new java.util.ArrayList<>()).add(new String[] {m.group(3), m.group(4)});
        return byRecipe;
    }

    private static int run(PrintStream capture, String... args) {
        PrintStream old = System.out;
        System.setOut(capture);
        try {
            return WeaveCLI.commandLine().execute(args);
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void everyRecipeAppliesToAFreshStarterAndThenPassesTheStrictCheckAndDrawsAGraph(@TempDir Path root) throws Exception {
        Map<String, List<String[]>> recipes = blocks();
        assertThat(recipes.keySet()).contains("different-model", "approve-before-publishing", "escalate-when-the-loop-gives-up", "add-agent", "web-search", "mask-personal-data");

        for (var recipe : recipes.entrySet()) {
            Path dir = root.resolve(recipe.getKey());
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            assertThat(run(new PrintStream(sink, true), "init", "pipeline", dir.toString(), "--flat")).as(sink.toString()).isZero();
            Path script = dir.resolve("main.loom");
            String text = Files.readString(script);

            String find = null;
            for (String[] block : recipe.getValue()) {
                if (block[0].equals("find")) {
                    find = block[1];
                } else if (block[0].equals("replace")) {
                    assertThat(find).as(recipe.getKey() + ": a replace comes after its find").isNotNull();
                    assertThat(text).as(recipe.getKey() + ": the lines to find are in the starter").contains(find);
                    assertThat(text.split(Pattern.quote(find), -1).length - 1).as(recipe.getKey() + ": and only once").isEqualTo(1);
                    text = text.replace(find, block[1]);
                    find = null;
                } else {
                    Path file = dir.resolve(block[0].substring("file=".length()));
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, block[1]);
                }
            }
            Files.writeString(script, text);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = run(new PrintStream(out, true), "check", script.toString(), "--no-env", "--strict");
            assertThat(code).as(recipe.getKey() + ":\n" + out).isZero();
            ByteArrayOutputStream graph = new ByteArrayOutputStream();
            assertThat(run(new PrintStream(graph, true), "graph", script.toString(), "--format", "mermaid")).as(recipe.getKey()).isZero();
            assertThat(graph.toString()).contains("flowchart");
        }
    }

    @Test
    void everyRecipeTellsTheReaderWhatToAskAnAgent() throws Exception {
        String page = Files.readString(Path.of("../../docs/guide/RECIPES.md"));
        long sections = page.lines().filter(l -> l.startsWith("## ")).count();
        long asks = page.lines().filter(l -> l.startsWith("> Ask your agent:")).count();
        assertThat(asks).isEqualTo(sections);
    }

    @Test
    void theTemplatesAndTheSkillPointAtTheRecipes() throws Exception {
        for (String t : List.of("pipeline", "approval", "classifier")) {
            assertThat(io.github.llm4j.loom.init.TemplateWriter.read(t, "README.md")).as(t).contains("weave guide recipes");
        }
        assertThat(Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"))).contains("weave guide recipes");
    }
}
