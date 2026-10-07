package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 6 tells an agent to write a custom tool (with a .loot file) and a task (with a services file) when something is missing. Its code is
 * not pseudo-code: this extracts every {@code file=} block, compiles the Java, and runs the tool and the task through the real executor.
 */
@Tag("fragile")
class GuideCustomCodeTest {

    private static Map<String, String> blocks() throws Exception {
        String chapter = Files.readString(Path.of("../../docs/guide/06-build-the-workflow.md"));
        Matcher m = Pattern.compile("```[a-z]+ file=(\\S+)\\n(.*?)```", Pattern.DOTALL).matcher(chapter);
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static Path write(Path dir, String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, text);
    }

    @Test
    void theToolAndTheTaskInTheGuideCompileAndRun(@TempDir Path dir) throws Exception {
        Map<String, String> blocks = blocks();
        assertThat(blocks).containsKeys("shop/WordCount.java", "shop/Slugify.java", "tools.loot", "META-INF/services/io.github.llm4j.agent.task.Task", "main.loom");

        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
        for (String name : blocks.keySet()) if (name.endsWith(".java")) args.add(write(dir.resolve("src"), name, blocks.get(name)).toString());
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertThat(compiled).as("the guide's Java compiles").isZero();
        write(classes, "META-INF/services/io.github.llm4j.agent.task.Task", blocks.get("META-INF/services/io.github.llm4j.agent.task.Task"));
        Path loot = write(dir, "tools.loot", blocks.get("tools.loot"));
        Path script = write(dir, "main.loom", blocks.get("main.loom"));

        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            // the tool: its class is the one the .loot file names, and it works
            String lootLine = Files.readAllLines(loot).stream().filter(l -> l.contains("=")).findFirst().orElseThrow();
            ToolRegistry tools = new ToolRegistry();
            Tool counter = (Tool) Class.forName(lootLine.split("=")[1].strip(), true, loader).getDeclaredConstructor().newInstance();
            tools.register(lootLine.split("=")[0].strip(), counter);
            assertThat(counter.execute(Map.of("text", "one two  three"))).isEqualTo("3");
            assertThat(counter.execute(Map.of("text", "  "))).isEqualTo("0");

            // the task, found through the services file, run by a workflow
            TaskRegistry tasks = TaskRegistry.discovered(loader);
            assertThat(tasks.get("Slugify")).isNotNull();
            for (var input : Map.of("Hello, World! 2026", "hello-world-2026").entrySet()) {
                List<TraceEvent> trace = new ArrayList<>();
                HarnessExecutor e = new HarnessExecutor(new LoomLoader().load(script.toString()), tools, new MockModels());
                e.setTaskRegistry(tasks);
                e.addTraceListener(trace::add);
                e.setBaseDir(dir);
                try {
                    e.initialize();
                    e.executeWorkflow("Main", Map.of("title", input.getKey()));
                } finally {
                    e.shutdown();
                }
                assertThat(trace).filteredOn(t -> t.type().equals(TraceEvent.NOTE)).extracting(TraceEvent::text).containsExactly("slug: " + input.getValue());
                assertThat(trace).extracting(TraceEvent::type).contains(TraceEvent.TASK_END);
            }
            HarnessExecutor refused = new HarnessExecutor(new LoomLoader().load(script.toString()), tools, new MockModels());
            List<TraceEvent> trace = new ArrayList<>();
            refused.setTaskRegistry(tasks);
            refused.addTraceListener(trace::add);
            try {
                refused.initialize();
                refused.executeWorkflow("Main", Map.of("title", "!!!"));
            } finally {
                refused.shutdown();
            }
            assertThat(trace).filteredOn(t -> t.type().equals(TraceEvent.NOTE)).extracting(TraceEvent::text)
                    .containsExactly("cannot publish: the title has nothing to make a slug from");
        }

        // and exactly as the guide says to run it: weave's own jar with the compiled classes beside it, through the real command line
        String cp = System.getProperty("java.class.path") + java.io.File.pathSeparator + classes;
        Process p = new ProcessBuilder("java", "-cp", cp, "io.github.llm4j.loom.cli.WeaveCLI", "check", script.toString(), "--loot", loot.toString(), "--no-env")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        assertThat(out).contains("Successfully loaded tool: WordCounter -> shop.WordCount").doesNotContain("is not defined").doesNotContain("unknown task");
    }

    @Test
    void theRuleAndTheSkillSayWhatIsMandatoryIsATask() throws Exception {
        String chapter = Files.readString(Path.of("../../docs/guide/06-build-the-workflow.md"));
        assertThat(chapter).contains("a mandatory activity is a task run with `run`, never an agent and never a tool").contains("## When something is missing: write it");
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));
        assertThat(skill).contains("mandatory").contains("`run`").contains(".loot").contains("META-INF/services/io.github.llm4j.agent.task.Task");
    }

    @Test
    void theGuideAndSkillSayTheAgentStillWritesTestsForItsCodeButWithTheProjectsOwnBuildAndNotWeave() throws Exception {
        String scope = "are still written (by the agent, as part of writing the code) with the project's own test framework and run by the project's own build (Maven, npm, and so on); `weave` does not run, provide a harness for, or check them";
        assertThat(Files.readString(Path.of("../../docs/guide/06-build-the-workflow.md"))).contains(scope);
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));
        assertThat(skill).contains(scope).contains("Loom's responsibility ends at the workflow").contains("Write unit tests for every tool, task, host and screen you write");
        assertThat(Files.readString(Path.of("src/main/resources/templates/_java-tests/src/test/README.md"))).contains("written in your own project and run by its own build; `weave` and this module do not run or check them");
        assertThat(Files.readString(Path.of("../../docs/guide/09-go-live.md"))).contains("The agent writes tests for the host and its screen too, in the application's own stack and run by its own build, not with `weave`.");
    }
}
