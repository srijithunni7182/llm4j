package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 26c, R15: the check that provenance cannot miss a route, against real data flow. Every
 * scripted model answer carries a canary (the mock model's words), and at every exit a value leaves
 * by, the runtime's belief "a model wrote this" is compared with whether the canary is in it. A
 * canary the runtime called not-model-written is a leak, found automatically.
 *
 * <p>It runs over every script in the repository, starters and CTK scenarios included, with mock
 * models and simulated tasks. It stays in CI for any change to the runtime, not only this phase's.
 */
class CanaryProvenanceOracleTest {

    static final String CANARY_WORD = "mock";
    static final long PER_WORKFLOW_SECONDS = 1;

    static List<Path> repositoryScripts() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        List<Path> scripts;
        try (Stream<Path> all = Files.walk(repo)) {
            scripts =
                    all.filter(p -> p.toString().endsWith(".loom") && Files.isRegularFile(p))
                            .filter(
                                    p ->
                                            !p.toString().contains("/target/")
                                                    && !p.toString().contains("/node_modules/")
                                                    // rule demonstrations: small flawed scripts
                                                    // written to make a review rule fire
                                                    && !p.toString().contains("/review-rules/"))
                            .sorted()
                            .toList();
        }
        Set<Path> imported = new HashSet<>();
        java.util.regex.Pattern importLine =
                java.util.regex.Pattern.compile("(?m)^\\s*import\\s+\"([^\"]+)\"");
        for (Path script : scripts) {
            var m = importLine.matcher(Files.readString(script));
            while (m.find()) imported.add(script.getParent().resolve(m.group(1)).normalize());
        }
        return scripts.stream().filter(p -> !imported.contains(p.normalize())).toList();
    }

    @Test
    void noValueWithModelTextEverLeavesTaggedAsNotModelWritten() throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "oracle-run");
                            t.setDaemon(true);
                            return t;
                        });
        List<String> leaks = java.util.Collections.synchronizedList(new ArrayList<>());
        int exits = 0;
        int scriptsRun = 0;
        for (Path path : repositoryScripts()) {
            LoomScript script;
            try {
                script = new LoomLoader().load(path.toString());
            } catch (Exception notAScript) {
                continue;
            }
            // One stop switch per workflow run is made below; tools, tasks and people answer only
            // while it is off, so nothing runs on in the background once a workflow has had its time.
            java.util.concurrent.atomic.AtomicBoolean stops = new java.util.concurrent.atomic.AtomicBoolean();
            ToolRegistry tools = new ToolRegistry();
            for (AgentDef a : script.getAgents())
                a.getTools()
                        .forEach(
                                t ->
                                        tools.register(
                                                t,
                                                new io.github.llm4j.agent.Tool() {
                                                    private final io.github.llm4j.agent.tools.EchoTool echo =
                                                            new io.github.llm4j.agent.tools.EchoTool();

                                                    @Override
                                                    public String getName() {
                                                        return echo.getName();
                                                    }

                                                    @Override
                                                    public String getDescription() {
                                                        return echo.getDescription();
                                                    }

                                                    @Override
                                                    public String execute(Map<String, Object> args) throws Exception {
                                                        if (stops.get()) throw new IllegalStateException("oracle: out of time");
                                                        return echo.execute(args);
                                                    }

                                                    @Override
                                                    public Map<String, Object> getParametersSchema() {
                                                        return echo.getParametersSchema();
                                                    }
                                                }));
            io.github.llm4j.agent.task.TaskRegistry tasks =
                    io.github.llm4j.agent.task.TaskRegistry.discovered();
            for (WorkflowDef w : script.getWorkflows())
                io.github.llm4j.loom.ast.StatementWalker.walk(
                        w.getStatements(),
                        st -> {
                            if (st instanceof io.github.llm4j.loom.ast.RunStmt r
                                    && !tasks.contains(r.getTaskName()))
                                tasks.register(
                                        io.github.llm4j.agent.task.Task.changes(
                                                r.getTaskName(),
                                                io.github.llm4j.agent.tool.EffectPolicy.DEFAULT,
                                                c -> {
                                                    if (stops.get()) throw new IllegalStateException("oracle: out of time");
                                                    return io.github.llm4j.agent.task.TaskResult.ok();
                                                }));
                        });
            for (WorkflowDef w : script.getWorkflows()) {
                int[] seen = {0};
                java.util.concurrent.Future<?> run = null;
                stops.set(false);
                try {
                    java.util.concurrent.atomic.AtomicBoolean stop = stops;
                    MockModels mock = new MockModels();
                    // Once a workflow has had its time, every model call fails, so a loop that
                    // would never end ends instead of running on in the background.
                    io.github.llm4j.loom.execution.LLMClientFactory factory =
                            model -> {
                                io.github.llm4j.LLMClient real = mock.createClient(model);
                                return new io.github.llm4j.LLMClient() {
                                    @Override
                                    public io.github.llm4j.model.LLMResponse chat(
                                            io.github.llm4j.model.LLMRequest request) {
                                        if (stop.get()) throw new IllegalStateException("oracle: out of time");
                                        return real.chat(request);
                                    }

                                    @Override
                                    public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(
                                            io.github.llm4j.model.LLMRequest request) {
                                        return java.util.stream.Stream.of(chat(request));
                                    }
                                };
                            };
                    HarnessExecutor executor = new HarnessExecutor(script, tools, factory);
                    executor.setTaskRegistry(tasks);
                    executor.setHumanInterface(
                            message -> {
                                if (stops.get()) throw new IllegalStateException("oracle: out of time");
                                return "ok";
                            });
                    executor.setEnvLookup(name -> "https://example.invalid/set-" + name);
                    executor.setBaseDir(path.toAbsolutePath().getParent());
                    executor.setSinkOracle(
                            (sink, value, modelWritten) -> {
                                seen[0]++;
                                if (String.valueOf(value).contains(CANARY_WORD) && !modelWritten)
                                    leaks.add(path.getFileName() + " / " + w.getName() + " → " + sink);
                            });
                    executor.initialize();
                    Map<String, String> inputs = new HashMap<>();
                    for (String parameter : w.getParameters()) inputs.put(parameter, "input");
                    run = pool.submit(() -> executor.executeWorkflow(w.getName(), inputs));
                    run.get(PER_WORKFLOW_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable runFailed) {
                    stops.set(true);
                    if (run != null) run.cancel(true); // a script that waits, loops or sleeps
                    // A script that needs a host, a key or a person may not get far. The exits it
                    // did reach are still judged.
                }
                exits += seen[0];
            }
            scriptsRun++;
        }
        pool.shutdownNow();
        assertThat(scriptsRun).as("scripts loaded").isGreaterThan(20);
        assertThat(exits).as("exits the oracle watched across the repository").isGreaterThan(5);
        assertThat(leaks).as("a canary the runtime called not model-written").isEmpty();
    }
}
