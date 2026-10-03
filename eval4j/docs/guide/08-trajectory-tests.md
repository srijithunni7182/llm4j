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

What Hexamind's [`TrajectoryPathTest`](../../../examples/hexamind-hub/src/test/java/io/github/llm4j/hexamind/eval/TrajectoryPathTest.java) checks for $0 on every build:
the **debunk path** (6 nodes), the **five-round path** (every agent delegated to five times), the exact **call count** (33: 30 agent rounds, the moderator, the coordinator and the hand-off),
**refinement** (8 calls), and that a **tiny budget stops the run** without producing a consensus.

`LoomTrace` maps parallel rounds to one node per round and reports the path as node ids; print `t.actualPath()` once to learn the ids for your script, then pin them.
Other assertions: `visitsInOrder`, `usesToolsInOrder`, `loopStopsWithin`, `rewindsAtMost`, `requestsApprovalBefore`, `guardHeld`, `outputMatchesSchema`, `staysWithinSpend`.

## Step 2: real models, one at a time

Run the same assertions on real models for a handful of representative cases, cheapest and most informative first: a normal question, a fabricated premise (the early-exit branch), a
user-feedback refinement. Keep the free checks and add judged ones (is the consensus specific and honest?), and gate on cost: if the first real run costs more than twice the model,
stop before the others ([chapter 9](09-go-live.md)). With real models, also assert the tools: every debate must search, and only through `Search`.

## Gate

Path, branch, round-count and budget-stop tests pass for free; the real runs take the expected path and their consensus passes its rubric.
