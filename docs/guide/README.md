# Building a multi-agent workflow the right way

A guided path from "which agents do I need?" to "my workflow runs against real APIs under a budget", using only
well-tested parts. Every chapter says what to do, why, shows a worked example from the **Hexamind Hub** (a real six-agent
debate app evaluated end to end with llm4j), and ends with a **gate**: the check that tells you it is safe to move on.

The rule that organises everything: **spend money last.** Each stage is first proved free (mocks, static checks), then run
for real under a cap.

## The map

| # | Stage | You | Tools | Cost | Gate to move on |
|---|---|---|---|---|---|
| 1 | [Decide your agents](01-decide-your-agents.md) | one job per agent; tools, persona, prompt ids | `AgentPersona`, `PromptRegistry` | $0 | each agent has a one-sentence job and a tool list |
| 2 | [Build a golden dataset](02-golden-dataset.md) | scenarios with rubrics and dimensions | `EvalScenarios`, `DatasetSynthesizer` | $0 (synthesis: small) | dataset test passes; every dimension has cases |
| 3 | [Test the prompts](03-prompt-tests.md) | rubric checks, A/B against a candidate | `llmJudged`, `PromptComparison` | cents | each prompt meets its rule |
| 4 | [Optimize the prompts](04-prompt-optimization.md) | only where tests fail | `PromptOptimizer` | capped | held-out test agrees (`generalized()`) |
| 5 | [Test each agent, with spend caps](05-test-agents-with-caps.md) | free on mocks, then real under a cap | `ScriptedClient`, `FakeJudge`, `SpendGuard`, `AgentReplay` | dollars, capped | agents meet their goals; judge noise known |
| 6 | [Build the workflow](06-build-the-workflow.md) | compose tested agents in Loom | Loom | $0 | the script loads |
| 7 | [Validate and audit](07-validate-and-audit.md) | static checks, security audit, injection tests | `weave check`, `weave audit` | $0 | no unexplained findings |
| 8 | [Test the trajectory](08-trajectory-tests.md) | path, branches, rounds, budget stop | scripted model, `LoomTrace`, `WorkflowAssertions` | $0, then capped | expected paths hold |
| 9 | [Go live: real APIs, budgets, cost checks](09-go-live.md) | staged rollout under caps | `weave run --max-cost`, `SpendGuard` | capped | spend within the model; limits set |
| 10 | [Best practices and checklist](10-best-practices.md) | | | | |

## The running example

[`examples/hexamind-hub`](../../examples/hexamind-hub): six personas (a technical analyst, a market strategist, a
futurist, a research scientist, a customer advocate and an adversarial source-checker) debate a question in five rounds,
a moderator checks whether the question contains a fabricated term, and a coordinator writes one consensus. The evaluation
lives in [`eval/`](../../examples/hexamind-hub/eval): [SPEC](../../examples/hexamind-hub/eval/SPEC.md),
[RUN-PLAN](../../examples/hexamind-hub/eval/RUN-PLAN.md), the [golden dataset](../../examples/hexamind-hub/eval/golden),
the [Loom workflow](../../examples/hexamind-hub/eval/hexamind.loom) and `run-all.sh`.

## What you need

- Java 17+, Maven, and the libraries: `ai-agent4j` (agents), `ai-agent4j-loom` (workflows), `eval4j` and `eval4j-report`
  (evaluation and the dashboard). See the [eval4j quick start](../../eval4j/docs/QUICKSTART.md) for the dependencies.
- A model for the agents and a **different, ideally stronger,** model for the judge.
- Keys in environment variables only, never in a file or the repository.

## Be honest about the edges

llm4j gives you a lot, and a few things you do by hand. The chapters say so where it matters: there is no
`weave estimate` for cost (you keep a small cost model); `weave audit` reads the script and cannot see what Java or MCP
tools do; and eval4j has no ready-made prompt-injection or PII-leak assertions (you write a hostile scenario and assert on
the tools used). A guide that hid these would send you into production overconfident.
