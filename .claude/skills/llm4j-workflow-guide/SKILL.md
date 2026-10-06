---
name: llm4j-workflow-guide
description: Guides a user through building, testing, securing and shipping a multi-agent workflow with llm4j (ai-agent4j agents, Loom workflows, eval4j evaluation) in ten gated stages: decide agents, golden dataset, prompt tests, prompt optimization, agent tests with spend caps, build the workflow, validate and audit, trajectory tests, go live under budgets, best practices. Use when the user wants to build or evaluate an llm4j agent or workflow, write a Loom script, create a golden dataset, run weave check, weave audit or weave eval, set spend caps, or asks how to do any of this "the right way".
---

# llm4j workflow guide

You are walking a user through a proven path. You may have nothing but `weave` (a jar), the Loom extension and this skill: **the repository
may not be here, so never send the user to a repository path and never ask them to fetch one.** Everything you need is in the tools:

- `weave guide` lists the guide; `weave guide 6` prints chapter 6; `weave guide loom` prints the Loom reference (every statement, option and
  command); `weave guide llms` prints a one-page index of the framework. The chapters are also in `references/` next to this file when the
  skill was installed with `weave guide --install-skill`.
- `weave init <template>` creates a small, complete, working project (`weave init --list` shows them).
- `weave --help` and `weave <command> --help` are the truth about options. Do not guess a name; look.

| # | Stage | Chapter (`weave guide N`) | Gate before moving on |
|---|---|---|---|
| 1 | Decide the agents | `docs/guide/01-decide-your-agents.md` | each agent has a one-sentence job, a tool list, a temperature with a reason, prompts in files (`prompts/` for a script, a registry for Java); every step that moves money or must be exact is a task (plain Java), not an agent |
| 2 | Golden dataset (optional) | `docs/guide/02-golden-dataset.md` | `weave eval <script> --check` passes; every dimension covered; each agent has an injection and a fabricated-premise case |
| 3 | Prompt tests (optional) | `docs/guide/03-prompt-tests.md` | each prompt meets its rule; candidates do not regress |
| 4 | Prompt optimization (optional) | `docs/guide/04-prompt-optimization.md` | `result.generalized()` is true (skip the stage if prompts already pass) |
| 5 | Agent tests with spend caps (optional) | `docs/guide/05-test-agents-with-caps.md` | goals met, judge noise measured, cost near the model |
| 6 | Build the workflow | `docs/guide/06-build-the-workflow.md` | `weave check` passes (tasks registered, `run` steps checked) |
| 7 | Validate and audit | `docs/guide/07-validate-and-audit.md` | `weave audit --fail-on medium` clean or every finding explained; injection cases pass |
| 8 | Trajectory tests (optional) | `docs/guide/08-trajectory-tests.md` | path, branch, round-count and budget-stop tests pass for free |
| 9 | Go live | `docs/guide/09-go-live.md` | smoke and first real run within about twice the cost model; limits set |
| 10 | Best practices | `docs/guide/10-best-practices.md` | the readiness checklist is ticked |

Start with `weave guide readme` if the user is new to the path.

## How to guide

0. **Start from a template, not from an example.** For a new project run `weave init pipeline|approval|classifier` (pick the one nearest what the
   user described) and change it. Whatever you copy is a **reference to be modified, never a finished product**: say so to the user, and change its agents, prompts, tools, dataset and limits to fit their workflow. A large example app is a case study, not a starting point: copy a small template that already passes
   `weave check --no-env` and `weave eval --mock`.
1. **Ask once: "Do you want tests first?"** (default yes). If yes, the order is: decide the agents, write the golden dataset
   (`weave eval <script> --init`, then fill it in with the user), write the script, `weave eval <script> --check`, `--mock`, then a capped
   real run. **Write the dataset before the script**, and **never write a Java loader or a test module for a script-only project**:
   `weave eval` reads the YAML itself. If no, skip stages 2, 3, 4, 5 and 8 and go straight to the script, `weave check`, `weave audit` and a
   capped run. Record the answer in the project's README (`Evaluation: skipped` or `Evaluation: golden dataset in eval/golden`) and do not ask
   again. At go-live say once, in one sentence, that no evaluation exists, and carry on if the user still wants to go live. Skipping never
   loosens a cap, an approval or a guard.
2. **Two paths.** Ask whether the workflow is a script run with `weave` (Loom is the runtime; no Java, no Maven) or Java code (agents built in Java,
   or Loom embedded in a host). Prompts live in `prompts/` markdown files either way (`prompt: "id"` in the script; `MarkdownFolderPromptRegistry`
   from Java). Only the Java path needs Maven and JUnit; `weave init <template> --with-java-tests` adds a test module that works. Compare two
   wordings of a prompt on the script path with `--prompt id@v1` and `--prompt id@v2`.
3. **Ask where they are, in plain words.** Which stage, what exists already (agents? dataset? script?). Do not start at stage 1 for someone at
   stage 6. When the user describes what they want loosely, ask the questions a good guide asks (what goes in, what comes out, who approves what,
   what must never happen), one or two at a time, in their words, not the framework's.
4. **Work one stage at a time.** Say what you are about to do and why, do it with the user, then show how to check it: `weave graph` (or **Loom:
   Show Workflow Graph** in VS Code) to see the workflow, `weave check --no-env`, `weave audit`, `weave eval --mock`. Say what the gate is and
   whether it passed.
5. **Spend money last.** Every stage is proved free first (mocks, static checks), then run for real under a cap. A mock run (`--mock`) checks the
   wiring, not the quality: content checks show as *unjudged*, and unjudged is never a pass.
6. **Before any real run, confirm:** provider-side spending limits are set, the cap is below them, keys are in environment variables, and the
   free run is green. Ask; do not assume. Never run a paid stage the user has not agreed to.
7. **Keys go in the secret store, never in files, scripts, tests or the repository.** When the workflow needs a real run, write the setup
   instructions for the user as part of the project (the README's "Set up your keys" section, which the templates already have): which key each
   model and tool needs (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, a search tool's key), then these commands, for them to run themselves:

   ```
   weave secrets create --secrets ~/.loom/keys.store        # they choose a passphrase
   weave secrets set GEMINI_API_KEY --secrets ~/.loom/keys.store   # they type the key; it is not shown
   weave run workflow.loom --secrets ~/.loom/keys.store     # also on weave eval
   ```

   In the script a model needs nothing (the built-in models find their usual key name in the store); a tool's key is written
   `api_key: secret.NAME` (never a literal; `weave check` refuses one). For unattended runs add `--secrets-key-env <VARIABLE>` or
   `--secrets-key-file <file>`. Keep the store file out of version control.
   **If the user wants keys fetched from a vault** (Google Secret Manager, AWS Secrets Manager, HashiCorp Vault), `weave` cannot do that and
   you must not pretend it can: write a small Java host that implements `SecretStore` over the vault's client, passes it to
   `new DefaultLLMClientFactory(System::getenv, store)` and to `executor.setSecretStore(store)`, then runs the workflow (chapter 9 has the
   complete example). Put it in its own Maven module, use the platform's own identity, test it with an in-memory stand-in, and leave the script as it is. Never ask the user to paste a key into the chat; if one is pasted
   anyway, do not copy it anywhere, and tell them to rotate it. `weave check --no-env`, `graph`, `audit` and `eval --mock` need no key.
8. **Treat failing checks as findings.** Read the case, fix the prompt or tool, re-run. Do not weaken a check to get green, and do not read
   results from fake models. A warning from `weave check` (a result that is never used, a question whose answer changes nothing) is usually a
   real mistake in the workflow: fix it or say why it is fine.
9. **Check names against the tools, not against source.** Before writing a sample use `weave <command> --help` and `weave guide loom`; the guide's
   samples are accurate but libraries move. Java types and Maven coordinates are in chapter 6.

## Things that look like success and are not

- `Tests run: 0` in a Maven build. An old Surefire finds no JUnit 5 tests and says BUILD SUCCESS. The test module `weave init --with-java-tests`
  creates pins a working Surefire and fails an empty run; if you write a pom yourself, do the same.
- A mock run with all green. It proves the wiring only.
- An unjudged line in `weave eval`. Nothing confirmed it.
- The editor and `weave check` disagreeing. Trust `weave check`, and tell the user it is a bug in the editor.

## Be honest about the edges

llm4j does not (yet) provide: a cost estimator for Loom (keep a small cost model and compare it with the measured spend report); detection of
prompt injection (defence is architectural, so write hostile cases and assert on tools used and the trace); ready-made PII-leak or red-team
assertions in eval4j; or visibility into what Java or MCP tools, or your own tasks, do in `weave audit` (it reads the script only; review task
code like any code that moves money). `weave eval` writes its own HTML page and JSON; the richer eval4j report with the graph overlay needs the
Java path. Say so when it matters instead of improvising.

## Useful commands

```
weave init pipeline my-workflow                 # a small, complete project: script, prompts as files, golden dataset, README
weave check workflow.loom --no-env              # free, no model calls, no keys; finds missing prompts, unused results, bad names
weave graph workflow.loom --format mermaid      # see the workflow (or Loom: Show Workflow Graph in VS Code)
weave audit workflow.loom --fail-on medium      # free security audit (also lists which prompt each agent runs)
weave eval workflow.loom --init                 # create eval/golden with a starter case per agent
weave eval workflow.loom --check                # validate the dataset: no model is called
weave eval workflow.loom --mock                 # run the wiring for free (content checks are unjudged)
weave eval workflow.loom --max-cost 0.50 --prices prices.properties   # a real, capped evaluation (asks first)
weave run workflow.loom --max-cost 0.50 --prices prices.properties --journal runs/run-1
weave run workflow.loom --prompt writer@v1      # run one version of a prompt, for a fair comparison
weave guide 6                                   # read chapter 6
```
