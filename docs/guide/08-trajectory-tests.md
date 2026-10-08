# 8. Test and verify the trajectory

**Goal:** prove the workflow takes the right path (branches, rounds, tools, budget stop), first for free on a scripted model, then on real models.

## Why

Agents can each pass their tests and the workflow can still be wrong: a branch never taken, a round skipped, a tool called by the wrong agent, a budget that does
not stop anything. A trajectory test asserts on the *shape* of a run, not the words.

## Step 1: free, on a scripted model

Loom takes its models from a factory, so a test hands it a fake. The agent protocol is JSON (a tool call or a `final_answer`); `ScriptedClient` speaks it.

```java
ScriptedClient model = new ScriptedClient()
        .whenSeen("Round 1 findings", ScriptedClient.reactFinal("It does not exist."))
        .whenSeen("Alex: ", ScriptedClient.reactFinal("{\"fabricated\": \"YES\", \"term\": \"QLL-7\"}"));
LLMClientFactory models = name -> model;                 // every agent uses the scripted model

HarnessExecutor executor = new HarnessExecutor(script, tools, models);
LoomTrace trace = LoomTrace.attach(executor)             // attach BEFORE initialize()
        .workflow(workflowDef).expectPath("start", "n2", "n4", "n5", "n7", "end");
executor.initialize();
executor.executeWorkflow("Collaborate", Map.of("problem", "Explain the Quantum Flux protocol"));
WorkflowTrace t = trace.finish(executor.spend());
```

Then assert (all free, none call a model):

```java
WorkflowAssertions.assertThat(t)
        .followsExpectedPath()                           // the nodes visited, in order
        .takesBranch("n5", "then")                       // the alt took its first (if) branch: the debunk; "else" is the full debate
        .invokesAgents("Alex", "Rahul")
        .delegatesToTimes("Alex", 5)                     // once per round: a plain agent list would show Alex once
        .callsOnlyAllowedTools(Set.of("Search"))
        .noSecretsInTrace();
```

## A complete test, with imports

This class compiles as it stands (it is checked against the real classes on every build). It loads the project's script the way `weave` does, runs it on a scripted model, and asserts on the path. Put it in `src/test/java`; the project from `weave init` already has the dependencies (`eval4j-report` is a test dependency; Loom brings the rest).

<!-- compiles-with-report -->
```java
package app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.evalreport.loom.LoomTrace;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowPathTest {

    private static final Path SCRIPT = Path.of("src", "main", "resources", "main.loom").toAbsolutePath();

    /** Runs workflow Main on the scripted model and returns what happened, as data to assert on. */
    private static WorkflowTrace run(ScriptedClient model, Map<String, String> inputs) throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), name -> model);   // every agent uses the scripted model
        executor.setHumanInterface(question -> "yes");           // the person's answer; change it to "no" to test the other path
        executor.setBaseDir(SCRIPT.getParent());
        executor.setEnvLookup(name -> "test");                    // no real key is read
        // Without this line a script that uses prompt files fails to load ("needs a prompt registry or prompt files"), because
        // `weave` builds the catalog from the folder next to the script and a test has to do it itself:
        executor.setPromptCatalog(PromptSupport.catalog(script, SCRIPT, PromptSettings.NONE));
        WorkflowDef main = script.getWorkflows().stream().filter(w -> w.getName().equals("Main")).findFirst().orElseThrow();
        LoomTrace trace = LoomTrace.attach(executor).workflow(main);   // attach BEFORE initialize()
        executor.initialize();
        try {
            executor.executeWorkflow("Main", inputs);
        } finally {
            executor.shutdown();
        }
        return trace.finish(executor.spend());
    }

    @Test
    void learnTheNodeIdsOnce() throws Exception {
        WorkflowTrace t = run(new ScriptedClient().otherwise(ScriptedClient.reactFinal("ok")), Map.of("topic", "x"));
        System.out.println(t.actualPath());   // copy these ids into followsExpectedPath() tests; delete this test afterwards
    }

    @Test
    void theFirstDraftIsRewrittenOnceThenAccepted() throws Exception {
        ScriptedClient model = new ScriptedClient()
                // whenTaskSeen looks only at the agent's own task. A request also carries the earlier agents' tasks and answers
                // ("Conversation History"), so whenSeen("...") can match a later agent because of what an earlier one said.
                .whenTaskSeen("Review", ScriptedClient.reactFinal("{\"verdict\": \"OK\"}"))
                // one rule, two answers in turn: the first writer call gets "draft", the second gets "better draft" (the last one repeats)
                .whenSeenThen("Write", ScriptedClient.reactFinal("draft"), ScriptedClient.reactFinal("better draft"))
                .otherwise(ScriptedClient.reactFinal("ok"));

        WorkflowTrace t = run(model, Map.of("topic", "composting"));

        WorkflowAssertions.assertThat(t)
                .invokesAgents("Writer", "Reviewer")
                .delegatesToTimes("Writer", 1)
                .runsTaskTimes("Publish", 1);          // a task has no model call: it can be asserted for free
        assertThat(model.calls()).as("model calls").isGreaterThan(0);
    }
}
```

What to know when you write these:

- **Replies are fixed text.** Use `whenSeenThen(needle, first, second, ...)` for "the first call gets one answer, the next call another". Without it every call matching a rule gets the same reply.
- **Every request carries the history** of the earlier agents in the run. Match on the agent's own task with `whenTaskSeen`, or put a marker in your `delegate` text ("Rewrite 1 of 2.") and match on that.
- **Tasks run for real in this test** unless you call `executor.setSimulateTasks(true)`; a task that changes things (the default effect) will do its change. Use a temporary folder, or simulate them. `weave eval --mock` and the starter's wiring test already simulate.
- A step answered from a `human_prompt` lambda is a free way to test both outcomes: run the same workflow with `"yes"` and with `"no"` and assert `runsTaskTimes("Publish", 0)` for the second.

A larger project (the Hexamind example in the repository, `TrajectoryPathTest`) checks for $0 on every build:
the **debunk path** (6 nodes), the **five-round path** (every agent delegated to five times), the exact **call count** (33: 30 agent rounds, the moderator, the coordinator and the hand-off),
**refinement** (8 calls), and that a **tiny budget stops the run** without producing a consensus.

`LoomTrace` maps parallel rounds to one node per round and reports the path as node ids; print `t.actualPath()` once to learn the ids for your script, then pin them.
**Tasks** (`run` steps, plain Java with no model) are nodes of kind `task` in the path and have their own assertions, which are free even on real runs because a task costs nothing:

```java
WorkflowAssertions.assertThat(t)
        .runsTasksInOrder("RefundPolicy", "IssueRefund")   // in this relative order (a step replayed from a journal did not run)
        .runsTaskTimes("Escalate", 0)                      // 0 proves a task never ran: the refund the policy refused was not paid
        .taskEndedWith("RefundPolicy", "approved");        // its outcome (the result's `outcome`)
```

A workflow made only of tasks needs no scripted model at all; a mixed one needs a scripted model only for its agent steps, and the number of model calls equals the number of agent steps.

Other assertions: `visitsInOrder`, `usesToolsInOrder`, `loopStopsWithin`, `rewindsAtMost`, `requestsApprovalBefore`, `guardHeld`, `outputMatchesSchema`, `staysWithinSpend`.

## Step 2: real models, one at a time

Run the same assertions on real models for a handful of representative cases, cheapest and most informative first: a normal question, a fabricated premise (the early-exit branch), a
user-feedback refinement. Keep the free checks and add judged ones (is the consensus specific and honest?), and gate on cost: if the first real run costs more than twice the model,
stop before the others ([chapter 9](09-go-live.md)). With real models, also assert the tools: every debate must search, and only through `Search`.

## Gate

Path, branch, round-count and budget-stop tests pass for free; the real runs take the expected path and their consensus passes its rubric.
