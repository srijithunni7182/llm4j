# 12. Java quick reference: the imports and calls you need

**Goal:** everything a project needs from Java, with the exact imports, so nobody has to open a jar or read source. Every block below is compiled on every build.

You need Java for three things only: **a host** (a program or screen that runs the workflow), **a task** (a step that must be exact code), and **tests**. The workflow itself stays in the `.loom` file.

## The pieces, in one compile-checked class

Each method is one thing you may want to do. Copy the one you need.

<!-- compiles-with-report -->
```java
package app;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.eval.dataset.EvalDataset;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.EnvSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class QuickReference {

    private QuickReference() {}

    /** Load a script and run workflow Main. The result is whatever the workflow stored with `-> name`. */
    static Object runOnce(Path scriptFile, LLMClientFactory models) throws Exception {
        LoomScript script = new LoomLoader().load(scriptFile.toAbsolutePath().toString());
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), models);
        executor.setBaseDir(scriptFile.toAbsolutePath().getParent());
        executor.setPromptCatalog(PromptSupport.catalog(script, scriptFile, PromptSettings.NONE));   // REQUIRED when agents use prompt files
        executor.initialize();                                                                         // add trace listeners and settings BEFORE this
        try {
            executor.executeWorkflow("Main", Map.of("topic", "composting"));                          // inputs are text, by parameter name
            return executor.getContext().getAll().get("draft");
        } finally {
            executor.shutdown();
        }
    }

    /** Which model client: the real one (keys from the environment or a store), a free fake, or a scripted one for tests. */
    static List<LLMClientFactory> modelChoices(SecretStore store) {
        LLMClientFactory real = new DefaultLLMClientFactory(System::getenv, store);
        LLMClientFactory free = new MockModels();                                                       // fixed, well-formed replies; costs nothing
        LLMClient scripted = new ScriptedClient().whenTaskSeen("Review", ScriptedClient.reactFinal("OK")).otherwise(ScriptedClient.reactFinal("ok"));
        LLMClientFactory test = name -> scripted;                                                        // every agent uses it
        return List.of(real, free, test);
    }

    /** Keys: an encrypted store (master key from an environment variable), or the process environment itself. */
    static SecretStore keys(Path storeFile, boolean useStore) {
        return useStore ? EncryptedFileSecretStore.open(storeFile, MasterKey.fromEnv("MYAPP_MASTER_KEY")) : EnvSecretStore.system();
    }

    /** Ask a person: block for the answer, or pause the run and come back (throw RunSuspended, answer into the journal, run again). */
    static void people(HarnessExecutor executor, Path journalFile) {
        executor.setHumanInterface(question -> "yes");                      // a lambda is enough for tests; a screen blocks on a future
        executor.setJournal(new FileRunJournal(journalFile));                // makes the run resumable
        executor.addTraceListener((TraceEvent event) -> System.out.println(event.type() + " " + event.text()));
    }

    static void pause(String stepId, String question) {
        throw new RunSuspended(stepId, question);                           // from inside promptHuman(stepId, message, hints)
    }

    /** What a run cost so far, and limits for it. */
    static String money(HarnessExecutor executor, Path prices) throws Exception {
        executor.setPriceTable(PriceTable.load(prices));                     // before initialize(): "model = input / output" per million tokens
        executor.setBudgetOverrides(50_000L, 20L, new BigDecimal("0.50"));    // tokens, calls, dollars; null means no limit of that kind
        var total = executor.spend().total();
        return total.calls() + " calls, " + total.tokens() + " tokens, cost " + total.cost();
    }

    /** A step that must be exact code. Register it in META-INF/services/io.github.llm4j.agent.task.Task, one class name per line. */
    public static final class Slugify implements Task {
        @Override public String getName() { return "Slugify"; }
        @Override public TaskEffect effect() { return TaskEffect.NONE; }      // NONE (pure), READS, or CHANGES (the default: simulated in mock runs)
        @Override public TaskResult run(TaskContext context) {
            String title = context.requireArg("title", String.class);
            return title.isBlank() ? TaskResult.rejected("empty title") : TaskResult.value(title.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        }
    }

    /** A tool an agent may call (listed in the agent's tools: [...]). */
    static void tool(ToolRegistry tools) {
        tools.register("Clock", new Tool() {
            @Override public String getName() { return "Clock"; }
            @Override public String getDescription() { return "Returns the current time"; }
            @Override public String execute(Map<String, Object> args) { return java.time.Instant.now().toString(); }
        });
    }

    /** Read the golden dataset from Java. */
    static int examples(Path datasetFolder) {
        EvalDataset dataset = EvalDataset.load(datasetFolder);
        if (!dataset.problems().isEmpty()) throw new IllegalStateException(dataset.problems().toString());
        List<EvalScenario> all = dataset.all();
        return all.size();
    }
}
```

## Where the other things are

| To do | Class (package) | Notes |
|---|---|---|
| Assert on the path of a run, for free | `LoomTrace` (`io.github.llm4j.evalreport.loom`), `WorkflowAssertions` (`io.github.llm4j.eval.assertions`), `WorkflowTrace` (`io.github.llm4j.eval.export`) | complete test in [chapter 8](08-trajectory-tests.md) |
| Scripted model with a reply sequence | `ScriptedClient.whenSeenThen`, `whenTaskSeen` (`io.github.llm4j.eval.testing`) | chapter 8 |
| Test a task with no model | `TaskContext.of(args, variables, stepId, key)` | [chapter 6](06-build-the-workflow.md) |
| Record results in the eval4j dashboard | `EvalRun`, `EvalChecks`, `MetricRef` (`io.github.llm4j.eval.export`) | the starter's `ScriptWiringTest` shows the pattern |
| A host with a screen | the complete class in [chapter 9](09-go-live.md) | blocking `HumanInterface`, spend, secret store |
| Describe, do not run, tasks that change things | `executor.setSimulateTasks(true)` | `weave eval --mock` does this |

## Rules that are easy to get wrong

- Add trace listeners and settings **before** `initialize()`.
- `TaskResult.value(x)` is a **static starter**; to add a value to a result you already have, use `.withValue(x)`.
- Workflow inputs and answers are **text**, compared exactly (`"yes"` is not `"Yes"`).
- `executor.setPromptCatalog(...)` is what makes prompt files work outside `weave`.
- A task registered through `META-INF/services` is found only after `mvn compile` (`weave` looks in `target/classes`).
