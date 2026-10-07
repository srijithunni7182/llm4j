# Building a multi-agent workflow the right way

A guided path from "which agents do I need?" to "my workflow runs against real APIs under a budget", using only
well-tested parts. Every chapter says what to do, why, shows a worked example from the **Hexamind Hub** (a real six-agent
debate app evaluated end to end with llm4j), and ends with a **gate**: the check that tells you it is safe to move on.

**Start from a template, not from an example.** `weave init pipeline` (or `approval`, `classifier`) creates a small, complete Maven project:
the script and its prompts as files in `src/main/resources`, the golden dataset (the eval tests) in `src/test/resources/eval/golden`, JUnit tests that run every scenario
(`mvn test`) and a README. Everything in it runs free: `weave check src/main/resources/main.loom --no-env`, `weave eval ... --mock`, `mvn test`.
The **Hexamind** examples in the chapters are a *case study*: a real six-agent app that runs its agents from Java and uses Loom for evaluation.
They show what a large evaluation looks like, but they are not the shape to copy for a script-only workflow, and the files they link to are in
the repository, not in the tools you installed (the links are web addresses for that reason).

The rule that organises everything: **spend money last.** Each stage is first proved free (mocks, static checks), then run
for real under a cap.

## Tests first, or skip them

Evaluation is **optional**. Before you start, decide: *do you want tests first?* The default is yes.

- **Yes:** decide the agents (1), write the golden dataset (2), then the script (6), then `weave eval <script> --check`, `--mock`
  and a capped real run. The dataset comes before the script, so "good" is written down before anything is built.
- **No:** go straight to the script (6), then `weave check` (7), `weave audit` (7) and a capped run (9). Chapters 2, 3, 4, 5 and 8 are
  skipped. Nothing else changes: caps, approvals, guards and the audit work the same, and an autonomy level that needs evidence still needs it.

Write your choice in the project's README (`Evaluation: skipped`, or `Evaluation: golden dataset in eval/golden`) so it is not asked again.
Skipping is not hidden: chapter 9 reminds you once that no evaluation exists, and you can start one later with `weave eval <script> --init`.

## The map

| # | Stage | You | Tools | Cost | Gate to move on |
|---|---|---|---|---|---|
| 1 | [Decide your agents](01-decide-your-agents.md) | one job per agent; tools, persona, prompt files; script path or Java path | `AgentPersona`, `PromptRegistry` | $0 | each agent has a one-sentence job and a tool list |
| 2 | [Build a golden dataset](02-golden-dataset.md) | scenarios with rubrics and dimensions (optional: see above) | `weave eval --init/--check`, `EvalScenarios`, `DatasetSynthesizer` | $0 (synthesis: small) | dataset test passes; every dimension has cases |
| 3 | [Test the prompts](03-prompt-tests.md) | rubric checks, A/B against a candidate | `llmJudged`, `PromptComparison` | cents | each prompt meets its rule |
| 4 | [Optimize the prompts](04-prompt-optimization.md) | only where tests fail | `PromptOptimizer` | capped | held-out test agrees (`generalized()`) |
| 5 | [Test each agent, with spend caps](05-test-agents-with-caps.md) | free on mocks, then real under a cap | `ScriptedClient`, `FakeJudge`, `SpendGuard`, `AgentReplay` | dollars, capped | agents meet their goals; judge noise known |
| 6 | [Build the workflow](06-build-the-workflow.md) | compose tested agents in Loom, and exact steps as tasks | Loom, `Task` | $0 | the script loads |
| 7 | [Validate and audit](07-validate-and-audit.md) | static checks, security audit, injection tests | `weave check`, `weave audit` | $0 | no unexplained findings |
| 8 | [Test the trajectory](08-trajectory-tests.md) | path, branches, rounds, budget stop | scripted model, `LoomTrace`, `WorkflowAssertions` | $0, then capped | expected paths hold |
| 9 | [Go live: real APIs, budgets, cost checks](09-go-live.md) | staged rollout under caps | `weave run --max-cost`, `SpendGuard` | capped | spend within the model; limits set |
| 10 | [Best practices and checklist](10-best-practices.md) | | | | |

## The running example

[`examples/hexamind-hub`](https://github.com/srijithunni7182/llm4j/tree/main/examples/hexamind-hub): six personas (a technical analyst, a market strategist, a
futurist, a research scientist, a customer advocate and an adversarial source-checker) debate a question in five rounds,
a moderator checks whether the question contains a fabricated term, and a coordinator writes one consensus. The evaluation
lives in [`eval/`](https://github.com/srijithunni7182/llm4j/tree/main/examples/hexamind-hub/eval): [SPEC](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/SPEC.md),
[RUN-PLAN](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/RUN-PLAN.md), the [golden dataset](https://github.com/srijithunni7182/llm4j/tree/main/examples/hexamind-hub/eval/golden),
the [Loom workflow](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/hexamind.loom) and `run-all.sh`.

## What you need

- Java 17+, Maven, and the libraries: `ai-agent4j` (agents), `ai-agent4j-loom` (workflows), `eval4j` and `eval4j-report`
  (evaluation and the dashboard). See the [eval4j quick start](https://github.com/srijithunni7182/llm4j/blob/main/eval4j/docs/QUICKSTART.md) for the dependencies.
- A model for the agents and a **different, ideally stronger,** model for the judge.
- Keys in environment variables only, never in a file or the repository.

## Use it as a Claude skill

The same path is available as a skill, so Claude can walk you through it stage by stage and check each gate: in this repo it is `.claude/skills/llm4j-workflow-guide/`, and `scripts/package-skill.sh` builds a standalone bundle (`dist/llm4j-workflow-guide.zip`) from these chapters for distribution.

## Be honest about the edges

llm4j gives you a lot, and a few things you do by hand. The chapters say so where it matters: there is no
`weave estimate` for cost (you keep a small cost model); `weave audit` reads the script and cannot see what Java or MCP
tools do; and eval4j has no ready-made prompt-injection or PII-leak assertions (you write a hostile scenario and assert on
the tools used). A guide that hid these would send you into production overconfident.
