package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D5 to D7 from a script: a sandbox names its workspace tool and takes its root and
 * store from it, a secret named for its environment is looked up when the program runs and never
 * shows in the result, and an http tool names the sandbox whose served program it calls.
 */
class SandboxScriptTest {

    static final String CANARY = "test-canary-0000000000";

    @TempDir Path dir;
    io.github.llm4j.loom.ast.LoomScript parsed;

    static final String TOOLS =
            """
            tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite  store: "snaps" }
            tool Run { use: sandbox  workspace: Data  isolation: process  network: open  allow: "printenv, touch"  unattended: true  env.TOKEN: env.SANDBOX_TOKEN }
            """;

    HarnessExecutor executor(String script) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Path file = Files.writeString(dir.resolve("main.loom"), script);
        parsed = new LoomLoader().load(file.toString());
        HarnessExecutor e =
                new HarnessExecutor(
                        parsed,
                        new ToolRegistry(),
                        m -> {
                            throw new IllegalStateException();
                        });
        e.setBaseDir(dir);
        e.setEnvLookup(k -> k.equals("SANDBOX_TOKEN") ? CANARY : null);
        return e;
    }

    List<String> errors(String script) throws Exception {
        HarnessExecutor e = executor(script);
        return new ScriptValidator()
                .validate(parsed, e.validationContext()).stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(ScriptValidator.Problem::message)
                        .toList();
    }

    @Test
    void aSandboxWorksOnItsWorkspaceAndASecretNeverShowsInTheResult() throws Exception {
        String script =
                TOOLS
                        + """
                        workflow Main() {
                            use Run(program = "touch", args = "[\\"made.txt\\"]")
                            use Run(program = "printenv", args = "[\\"TOKEN\\"]") -> shown
                            use Data(action = "write", path = "printed.txt", content = "{shown.stdout}")
                        }
                        """;
        assertThat(errors(script)).as("weave check").isEmpty();
        HarnessExecutor e = executor(script);
        e.initialize();
        // a script has no list literal: it gives the text of a JSON list
        StringBuilder trace = new StringBuilder();
        e.addTraceListener(event -> trace.append(event).append('\n'));
        e.executeWorkflow("Main", Map.of());
        assertThat(dir.resolve("work/made.txt")).exists();
        assertThat(trace).doesNotContain(CANARY);
        assertThat(Files.readString(dir.resolve("work/printed.txt")).strip())
                .as("the program printed the secret; the result carries the redaction mark")
                .isEqualTo("***");
        try (var files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain(CANARY);
        }
    }

    @Test
    void aMissingContainerRuntimeStopsTheRunBeforeItsFirstStep() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                onPath("podman") == null, "podman is installed here, so it is not missing");
        String script =
                """
                tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite }
                tool Box { use: sandbox  workspace: Data  isolation: container  runtime: podman  image: "alpine:3.20"  allow: "ls"  unattended: true }
                workflow Main() {
                    use Data(action = "write", path = "first.txt", content = "ran")
                    use Box(program = "ls")
                }
                """;
        assertThat(errors(script)).as("weave check looks at the script, not this machine").isEmpty();
        HarnessExecutor e = executor(script);
        e.initialize();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()))
                .hasMessageContaining("podman")
                .hasMessageContaining("never falls back");
        assertThat(dir.resolve("work/first.txt")).as("no step ran").doesNotExist();
    }

    static Path onPath(String name) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (!d.isBlank() && Files.isExecutable(Path.of(d, name))) return Path.of(d, name);
        return null;
    }

    @Test
    void theNamedToolMustBeAWorkspaceAndItsOptionsAreNotWrittenTwice() throws Exception {
        assertThat(errors(TOOLS.replace("workspace: Data", "workspace: Nope") + "workflow Main() { }"))
                .anyMatch(p -> p.contains("names no tool Nope"));
        assertThat(
                        errors(
                                TOOLS
                                        + "tool Other { use: compute }\n"
                                        + "tool Bad { use: sandbox  workspace: Other  isolation: process  network: open  allow: \"ls\"  unattended: true }\n"
                                        + "workflow Main() { }"))
                .anyMatch(p -> p.contains("Other is a compute tool, not a workspace tool"));
        assertThat(errors(TOOLS.replace("workspace: Data", "workspace: Data  workspace.root: \"elsewhere\"") + "workflow Main() { }"))
                .anyMatch(p -> p.contains("is taken from the tool workspace: names"));
        assertThat(errors(TOOLS.replace("env.SANDBOX_TOKEN", "\"literal\"") + "workflow Main() { }"))
                .anyMatch(p -> p.contains("must name a secret or a variable"));
        assertThat(errors(TOOLS.replace("isolation: process  ", "") + "workflow Main() { }"))
                .anyMatch(p -> p.contains("needs isolation"));
    }

    @Test
    void anHttpToolReachesTheSandboxesServedProgram() throws Exception {
        String script = TOOLS + "tool App { use: http  reaches: Run }\nworkflow Main() { }";
        assertThat(errors(script)).isEmpty();
        assertThat(errors(TOOLS + "tool App { use: http  reaches: Data }\nworkflow Main() { }"))
                .anyMatch(p -> p.contains("Data is a workspace tool, not a sandbox tool"));
    }
}
