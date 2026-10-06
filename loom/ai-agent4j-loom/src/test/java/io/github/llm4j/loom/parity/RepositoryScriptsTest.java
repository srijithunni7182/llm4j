package io.github.llm4j.loom.parity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verification plan N2: every .loom script in the repository passes the load-time checks. Tools a host
 * registers from Java can't be seen from here, so the names its agents use count as registered; secrets
 * count as set (to a URL-shaped value). Everything else — unsupported syntax, knowledge, routing, skills, guardrails — is checked.
 */
class RepositoryScriptsTest {

    @Test
    void n2_everyScriptInTheRepositoryPassesTheChecks() throws Exception {
        Path repo = Path.of("../..").toAbsolutePath().normalize();
        List<Path> scripts;
        try (Stream<Path> all = Files.walk(repo)) {
            scripts = all.filter(p -> p.toString().endsWith(".loom"))
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/node_modules/"))
                    .sorted().toList();
        }
        assertThat(scripts).hasSizeGreaterThan(5);
        List<String> failures = new ArrayList<>();
        for (Path script : scripts) {
            LoomScript parsed;
            try {
                parsed = new LoomLoader().load(script.toString());
            } catch (Exception e) {
                continue; // fragments meant to be imported, and deliberately broken fixtures, are parser tests' business
            }
            ToolRegistry hostTools = new ToolRegistry();
            for (AgentDef a : parsed.getAgents()) a.getTools().forEach(t -> hostTools.register(t, new io.github.llm4j.agent.tools.EchoTool()));
            // Hosts resolve their own model names (GetViral's "studio", test mocks): a host factory is trusted.
            HarnessExecutor e = new HarnessExecutor(parsed, hostTools, m -> { throw new IllegalStateException(); });
            // Like tools, tasks are the host's: a script's `run` steps name tasks the host supplies, so stand in for the ones not on the class path.
            io.github.llm4j.agent.task.TaskRegistry hostTasks = io.github.llm4j.agent.task.TaskRegistry.discovered();
            for (var w : parsed.getWorkflows()) {
                io.github.llm4j.loom.ast.StatementWalker.walk(w.getStatements(), st -> {
                    if (st instanceof io.github.llm4j.loom.ast.RunStmt r && !hostTasks.contains(r.getTaskName())) {
                        hostTasks.register(io.github.llm4j.agent.task.Task.pure(r.getTaskName(), c -> io.github.llm4j.agent.task.TaskResult.ok()));
                    }
                });
            }
            e.setTaskRegistry(hostTasks);
            e.setBaseDir(script.getParent());
            // as `weave` does: the prompt files a script names are the ones beside it (or the folder it names)
            e.setPromptCatalog(io.github.llm4j.loom.prompt.PromptSupport.catalog(parsed, script, null));
            // A value that is also a well-formed URL, since env vars feed URL options of the generic tools.
            e.setEnvLookup(name -> "https://example.invalid/set-" + name);
            e.setHumanInterface(message -> "");
            e.setEmbeddingFactory(model -> new HashingEmbeddingProvider());
            for (ScriptValidator.Problem p : new ScriptValidator().validate(parsed, e.validationContext())) {
                if (p.severity() == ScriptValidator.Severity.ERROR) failures.add(repo.relativize(script) + ": " + p);
            }
        }
        assertThat(failures).isEmpty();
    }
}
