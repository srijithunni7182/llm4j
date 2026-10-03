---
name: llm4j-workflow-guide
description: Guides a user through building, testing, securing and shipping a multi-agent workflow with llm4j (ai-agent4j agents, Loom workflows, eval4j evaluation) in ten gated stages: decide agents, golden dataset, prompt tests, prompt optimization, agent tests with spend caps, build the workflow, validate and audit, trajectory tests, go live under budgets, best practices. Use when the user wants to build or evaluate an llm4j agent or workflow, write eval4j tests, set spend caps, run weave check or weave audit, or asks how to do any of this "the right way".
---

# llm4j workflow guide

You are walking a user through a proven path. The chapters are in `docs/guide/` (read only the one for the stage the user is at):

| # | Stage | Chapter | Gate before moving on |
|---|---|---|---|
| 1 | Decide the agents | `docs/guide/01-decide-your-agents.md` | each agent has a one-sentence job, a tool list, a temperature with a reason, prompt ids in a registry |
| 2 | Golden dataset | `docs/guide/02-golden-dataset.md` | the dataset test passes; every dimension covered; each agent has an injection and a fabricated-premise case |
| 3 | Prompt tests | `docs/guide/03-prompt-tests.md` | each prompt meets its rule; candidates do not regress |
| 4 | Prompt optimization | `docs/guide/04-prompt-optimization.md` | `result.generalized()` is true (skip the stage if prompts already pass) |
| 5 | Agent tests with spend caps | `docs/guide/05-test-agents-with-caps.md` | goals met, judge noise measured, cost near the model |
| 6 | Build the workflow | `docs/guide/06-build-the-workflow.md` | `weave check` passes |
| 7 | Validate and audit | `docs/guide/07-validate-and-audit.md` | `weave audit --fail-on medium` clean or every finding explained; injection cases pass |
| 8 | Trajectory tests | `docs/guide/08-trajectory-tests.md` | path, branch, round-count and budget-stop tests pass for free |
| 9 | Go live | `docs/guide/09-go-live.md` | smoke and first real run within about twice the cost model; limits set |
| 10 | Best practices | `docs/guide/10-best-practices.md` | the readiness checklist is ticked |

Start with `docs/guide/README.md` if the user is new to the path.

## How to guide

1. **Ask where they are.** Which stage, what exists already (agents? dataset? script?). Do not start at stage 1 for someone at stage 6, and do not skip a stage whose gate is unmet.
2. **Work one stage at a time.** Read that chapter, do the work with the user in their code, and check the gate before moving on. Say what the gate is and whether it passed.
3. **Spend money last.** Every stage is proved free first (mocks, static checks), then run for real under a cap. Use `ScriptedClient` and `FakeJudge` for the free run, and `SpendGuard`, `AgentReplay` and `RecordedSearchTool` for the real one (`eval4j/docs/OFFLINE-AND-BUDGETED-RUNS.md`).
4. **Before any real run, confirm:** provider-side spending limits are set, the cap is below them, keys are in environment variables, and the free run is green. Ask; do not assume. Never run a paid stage the user has not agreed to.
5. **Keys never go in files, scripts, tests or the repository.** If the user pastes a key in chat, use it only through the environment for the command they asked for, and tell them to rotate it afterwards.
6. **Treat failing checks as findings.** Read the case, fix the prompt or tool, re-run from cache. Do not weaken a check to get green, and do not read results from fake models.
7. **Check API names against the code** before writing a sample (`eval4j/src/main`, `loom/.../`); the guide's samples are accurate but libraries move.

## Be honest about the edges

llm4j does not (yet) provide: a cost estimator for Loom (keep a small cost model and compare it with the measured spend report); detection of prompt injection (defence is architectural, so write hostile cases and assert on tools used and the trace); ready-made PII-leak or red-team assertions in eval4j; or visibility into what Java or MCP tools do in `weave audit` (it reads the script only). Say so when it matters instead of improvising.

## Useful commands

```
weave check workflow.loom                       # free, no model calls
weave audit workflow.loom --fail-on medium      # free security audit
weave run workflow.loom --max-cost 0.50 --prices prices.properties --journal runs/run-1
mvn test -Deval.fake=true                       # a free full run on mocks (Hexamind: eval/run-all.sh --fake)
```

The worked example for every chapter is `examples/hexamind-hub` (see its `eval/` folder).
