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
| 11 | Build the test examples with the user | `docs/guide/11-build-the-dataset-with-the-user.md` | every agent: the user answered the five quality questions, countable things are checked by code, no silent gaps |

Start with `weave guide readme` if the user is new to the path. When the user wants to change a starter, `weave guide recipes` has tested before-and-after changes (a different model, ask a person before publishing, escalate when a loop gives up, add an agent, add a tool, mask personal data); use them as patterns and give the user the matching "Ask your agent" sentence.

## How to guide

0. **Start from a template, not from an example.** For a new project run `weave init pipeline|approval|classifier` (pick the one nearest what the
   user described) and change it. Whatever you copy is a **reference to be modified, never a finished product**: say so to the user, and change its agents, prompts, tools, dataset and limits to fit their workflow. A large example app is a case study, not a starting point: copy a small template that already passes
   `weave check --no-env` and `weave eval --mock`.
   `weave init <template> <dir>` makes a **Maven project**. Right after it runs, tell the user in plain words what each part is for, using the paths the command
   printed: `src/main/resources/main.loom` is the workflow and `src/main/resources/prompts/` its prompts (they travel with the program);
   `src/test/resources/eval/golden/` is the **golden dataset, which is the eval tests**: example requests and what a good answer does;
   `src/test/java/starter/` are eval4j JUnit tests that run every scenario of that dataset as its own test case, on a model that costs nothing (`mvn test`);
   `pom.xml` builds it; `.env.example` is where a key goes (copy it to `.env`, which git ignores). The commands take the script's path:
   `weave check src/main/resources/main.loom --no-env`, and `weave next` from the project root says what to do next. Never copy files out of a scratch
   folder by hand, and never rearrange the layout: `weave init <template> --flat` makes the older flat folder if the user really wants one.
1. **Ask once: "Do you want tests first?"** (default yes). If yes, the order is: decide the agents, write the golden dataset
   (`weave eval <script> --init`, then fill it in with the user), write the script, `weave eval <script> --check`, `--mock`, then a capped
   real run. **Write the dataset before the script, and write it WITH the user, not for them**: read `weave guide 11` and run that conversation (teach in plain words, ask five short questions per agent, collect their real example requests, show samples and ask good-or-bad, read the list back, only then write the file), and **never write a Java loader or a test module for the dataset**: the project
   from `weave init` already has the JUnit tests that run it (`mvn test`), and `weave eval` reads the same YAML. If no, skip stages 2, 3, 4, 5 and 8 and go straight to the script, `weave check`, `weave audit` and a
   capped run. Record the answer in the project's README (`Evaluation: skipped` or `Evaluation: golden dataset in <its folder>`) and do not ask
   again. At go-live say once, in one sentence, that no evaluation exists, and carry on if the user still wants to go live. Skipping never
   loosens a cap, an approval or a guard.
2. **Two paths.** Ask whether the workflow is a script run with `weave` (Loom is the runtime; you write no Java for the workflow itself) or Java code (agents built in Java,
   or Loom embedded in a host). Prompts live in `prompts/` markdown files either way (`prompt: "id"` in the script; `MarkdownFolderPromptRegistry`
   from Java). Maven and JUnit come with the project `weave init` makes (its tests run the dataset); your own Java (tasks, tools, a host) goes in `src/main/java` of that same project. Compare two
   wordings of a prompt on the script path with `--prompt id@v1` and `--prompt id@v2`.
   **If the user is building an application with a screen** (a web page, a desktop app, a chat window with a transcript), the workflow stays a
   `.loom` file and the application is Java code you write in their project: a host that attaches `addTraceListener` for the transcript (before
   `initialize()`), a `HumanInterface` that asks the person through their screen (throwing `RunSuspended` when the answer comes later, then
   `journal.answer` and run again), and reads results from `executor.getContext().getAll()`. The interface itself is theirs and in the stack
   they choose; the section "An application with its own interface" in chapter 9, and its part "Plugging the host into the workflow", have complete, compiling code for the `HumanInterface` (waiting for the person's answer, or pausing the run) and for the keys. Keep decisions in the script, not the screen.
2b. **Turn the request into hard requirements, in plain words, and say which are checked by code.** Right after you understand the request, list each measurable
   thing the user asked for ("at least 500 words", "at most 2 rounds", "always asks a person before saving", "never shows a card number") and mark it
   **checked by code** or **judged by a model**. Anything countable or exact is code: a length is `expected_min_words` / `expected_max_words` in the dataset (a fixed check; a mock run
   leaves it unjudged), a forbidden text is `expected_output_not_contains`, a step that must always happen is a task or a `run`, not a prompt. Write the prompt wording to match the number
   ("at least 500 words", not "about 500") and ask if a limit is a minimum or a maximum when it is not clear. A requirement that is only in a prompt is a wish, not a requirement. Do not call the build done until each
   code-checked one has a scenario that fails when it is broken and passes when it is kept, and read at least one real answer (under an agreed cap) before saying the content is right.
3. **Ask where they are, in plain words.** Which stage, what exists already (agents? dataset? script?). Do not start at stage 1 for someone at
   stage 6. When the user describes what they want loosely, ask the questions a good guide asks (what goes in, what comes out, who approves what,
   what must never happen), one or two at a time, in their words, not the framework's.
4. **Work one stage at a time.** Say what you are about to do and why, do it with the user, then show how to check it: `weave graph` (or **Loom:
   Show Workflow Graph** in VS Code) to see the workflow, `weave check --no-env`, `weave audit`, `weave eval --mock`. Say what the gate is and
   whether it passed.
5. **Spend money last.** Every stage is proved free first (mocks, static checks), then run for real under a cap. A mock run (`--mock`) checks the
   wiring, not the quality: content checks show as *unjudged*, and unjudged is never a pass.
6. **Before any real run, confirm:** provider-side spending limits are set, the cap is below them, the key is in `.env` (developer) or the secret store (deployer), and the
   free run is green. Ask; do not assume. Never run a paid stage the user has not agreed to.
7. **Keys: two different people, two different answers. Ask which one this is.**
   - **A developer running the workflow and its evaluations on their own machine** (including an LLM as judge): a **`.env` file beside the script**, ignored by git.
     `weave init` writes `.env.example` and a `.gitignore` that already ignores `.env`; tell the user to run `cp .env.example .env` and put the key after the `=`. `weave run`,
     `weave check`, `weave eval` and `weave next` read it and say which names they found (never the values); a variable already set in their shell wins. `weave`
     refuses a `.env` that git tracks. A judge from another provider needs that provider's key in the same file.
   - **An application you write for the user** (a web page, a desktop or chat app; a Java host that runs the workflow): this is the deployer case even on their own laptop. The app's model key comes from the **secret store** (`weave secrets create`, `weave secrets set NAME`; in the host, `EncryptedFileSecretStore` with a master key from an environment variable) or from environment variables the platform sets. **Never put it in `.env` or `.env.example`**: `.env` is only for `weave` run by a developer, and for the model that judges evaluations. Chapter 9, "Plugging the host into the workflow", has the complete code.
   - **Someone deploying the application** (a server, shared or production keys): the **secret store** (`weave secrets create`, `weave secrets set NAME`, then `--secrets <file>`
     on `weave run`; unattended runs add `--secrets-key-env <VARIABLE>`) or, for a vault, the Java host in chapter 9. **Never the project's `.env` on a server.**

   In the script a model needs nothing (the built-in models find their usual key name); a tool's key is written `api_key: env.NAME` or `secret.NAME` (never a literal;
   `weave check` refuses one). **Never read, open, print or paste the contents of `.env`, and never ask the user to paste a key into the chat**; if one is pasted anyway,
   do not copy it anywhere and tell them to rotate it. `weave check --no-env`, `graph`, `audit`, `explain`, `next` and `eval --mock` need no key.
   **If the user wants keys fetched from a vault** (Google Secret Manager, AWS Secrets Manager, HashiCorp Vault), `weave` cannot do that and you must not pretend it can: write a
   small Java host that implements `SecretStore` over the vault's client, passes it to `new DefaultLLMClientFactory(System::getenv, store)` and to `executor.setSecretStore(store)`,
   then runs the workflow (chapter 9 has the complete example). Put it in its own Maven module, use the platform's own identity, test it with an in-memory stand-in for the vault (never a real key), and leave the script as it is.
8. **Treat failing checks as findings.** Read the case, fix the prompt or tool, re-run. Do not weaken a check to get green, and do not read
   results from fake models. A warning from `weave check` (a result that is never used, a question whose answer changes nothing) is usually a
   real mistake in the workflow: fix it or say why it is fine.
9. **Check names against the tools, not against source.** Before writing a sample use `weave <command> --help` and `weave guide loom`; the guide's
   samples are accurate but libraries move. Java types and Maven coordinates are in chapter 6.

10. **If something is missing, write it; do not leave a gap or a placeholder.** The agent building the workflow writes the Java (the user reviews it), in
    their project, and tests it:
    - *A capability an agent may choose to call* is a **custom tool**: a class implementing `io.github.llm4j.agent.Tool`, mapped in a **`.loot` file**
      (`WordCounter = shop.WordCount`), named in the agent's `tools: [...]`, run with `--loot tools.loot`. A tool that acts on the world asks first
      (`requiresApproval`, and `approve: [Name]` in the script).
    - *An activity that must happen every time* (a rule, a calculation, a record, a payment, a notification) is a **task**: a class implementing
      `io.github.llm4j.agent.task.Task`, listed in `META-INF/services/io.github.llm4j.agent.task.Task`, used with `run Name(arg = value) -> result_var`.
      **A mandatory activity is a task run with `run`, never an agent and never a tool** (a model can skip, repeat or be talked out of a step); the only
      mandatory step that is not a task is asking a person (`human_prompt`, approvals). Say which one you chose and why.
    - Give a task the right effect (`NONE`, `READS`, `CHANGES`) and an idempotency key for anything that changes the world. Loom checks only the wiring: `weave check`
      with the classes on the class path. Tests for tasks, tools, an application's screen, or any other code a developer or an agent writes are still written (by the agent, as part of writing the code) with the project's own test framework and run by the project's own build (Maven, npm, and so on); `weave` does not run, provide a harness for, or check them. Write unit tests for every tool, task, host and screen you write, as part of writing it (given this input, this output, including the refusal), and do not use `weave` or build Loom-specific scaffolding for them. The `weave` jar alone does not see the project's classes: run
      `java -cp weave.jar:classes io.github.llm4j.loom.cli.WeaveCLI check main.loom --loot tools.loot --no-env` (chapter 6 has the complete example).
    - Name result variables so they cannot be ordinary words in a string (`slug_result`, not `slug`).

11. **Keep each `.loom` file readable: split before it passes about 150 lines.** Entry file short (imports, `budget`, `audit`, `rate_limits`, and a top-level
    workflow that reads like a table of contents); agents in `agents/*.loom`; one file per phase or reusable sub-workflow in `flows/*.loom`, run with `call`.
    Remember: `call` passes text and hands back the variable named `result`; names are one flat namespace across files (make them unique); `budget` goes
    in the entry file only. Check with `weave check` and `weave graph` on the entry file, and tell the user how you split it. Chapter 6 has the layout and a complete example.

12. **Know where the project stands, and show what you built.** In a project that already has files, run `weave next` first: it says what to do next, in order, and
    it is free. After you change a script, run `weave explain <script>` and read the plain-English description back to the user, so they can confirm it is what they
    meant before anything is run. Neither command calls a model. When the user asks how to change something, look in `weave guide recipes` for a tested pattern.

13. **If any agent uses a built-in tool (`webhook`, `email`, `http`, `file`, `shell`, `sql`), prepare it for deployment.** Write a "Deploying" section in the project's README with one line per name the
    script needs (get them from `weave check <entry> --no-env`, "not set yet"): what it is for and how the deployer sets it. Then, from chapter 9 ("Built-in tools when deployed"):
    - keep a **development entry** (email `outbox:` instead of SMTP, a test webhook URL) and a **deployed entry** that import the same workflows; tools are declared in the entry file;
    - every credential, webhook URL and SMTP password is `env.NAME` or `secret.NAME` and is supplied by the service's environment, the secret store or a vault host, **never the project's `.env`**;
    - settle approvals for a workflow nobody watches (`approve:` needs `--ask-via` or a host `HumanInterface`; a `shell` tool needs `approve:` or `unattended: true`), a durable `--journal`, `max_per_run` for
      email, `allow_paths` and `methods` for `http`, a writable persistent folder for `file`, installed programs for `shell`, and a **read-only database user** for `sql`;
    - tell the user to run `weave check <deployed entry>` (without `--no-env`) on the target machine, then `weave audit --fail-on medium`, then a capped smoke run to a test sink before the real recipients.

14. **Round up before you talk about money.** When the workflow is built and the free checks pass, finish the job in this order. Do not end on "the next step costs money".
    a. **Build it.** Run `mvn test` (the project's own build, **without `-q`** so the totals show) and say the result in one line ("13 tests, 0 failures"). `Tests run: 0` is a failure.
    b. **Write `PROJECT_REPORT.md`** at the project root, from the template below, and tell the user where it is. Take the facts from the tools, not from memory:
       `weave explain <script>` for the steps, `weave graph <script> --format mermaid` for the picture, `weave audit <script>` for the safety summary.
    c. **Open the eval4j dashboard.** `mvn test` wrote `target/eval4j/report/index.html`. Open it for the user in their browser (`open` on macOS, `xdg-open` on Linux,
       `start` on Windows); if you cannot open a window, give the full path and say so. Tell them what they are looking at: **Wiring 100%** means every example ran through
       the workflow end to end on a fake model; **Safety** and **Tone** (the dataset's quality dimensions) read "Declared, not evaluated" because only a real, capped run judges
       them; the large percentage at the top is the wiring only, not the quality. Offer `weave graph` or the editor's **Loom: Show Workflow Graph** to look at the workflow itself.
    c2. **Update the project's `README.md`** so it matches what now exists (the agents, the checks, the commands, where the report is) and ticks off the stages done.
    d. **Then, last, how to set up and run it for real.** Write it in the report and say it in chat, in this order, and run none of it without a yes: what a run costs and what stops
       it (the script's `budget`, `--max-tokens`, the provider's own spending limit); getting a key from the provider; run `cp .env.example .env` for the user (it only copies a template; do not open the result) and have the user type the key after the
       `=` (never ask for it in chat, never print `.env`); `weave check <script>` without `--no-env`; one capped run (`weave run <script> -i name="..." --max-tokens 50000`);
       the capped real evaluation (`weave eval <script> --max-tokens 200000 --report target/eval-report.html`, which says what it will do and asks first); and, when they are happy,
       how to put it on a server (`weave guide 9`: the secret store, not `.env`).

    Template for `PROJECT_REPORT.md` (fill every section; say "none" rather than dropping one):

    ```markdown
    # <project>: what was built
    ## What you asked for
    <their request, in their words, and the changes of mind along the way>
    ## What was built
    | Agent | Model | What it does | Tools | Guards |
    |---|---|---|---|---|
    ## How it works
    <the mermaid graph from weave graph, then the numbered steps from weave explain>
    ## What keeps it safe
    <the budget; personal-data guards; who approves what, and the rule that decides it; the audit result: findings and why each is acceptable>
    ## What was checked, and what was not
    - Build: <mvn test result>. Golden dataset: <n> scenarios in <folder>. The mock run proves the wiring only.
    - Not yet evaluated: <the quality dimensions>. No real model has been called and nothing has been spent.
    ## Where things are
    <the tree, one line per file or folder>
    ## Set up and run it for real
    <the steps from (d), with this project's real commands and cap>
    ```

## Show the stages as a checklist

At the start, and again after every stage, print a short checklist in the chat and tick what is done, so the user always sees where they are and what is left:

```
[x] Understand the request and the hard requirements
[x] Agents decided
[ ] Test examples written with you
[ ] Workflow script written and checked (free)
[ ] Free practice run and audit
[ ] Build, report and dashboard
[ ] Real run (costs money: only with your yes)
```

Adjust the lines to the project; never tick a line that was not actually done.

## Explain as you go

The user may be new to all of this, so before each stage say, in one or two plain sentences: what you are about to do, why, what they will see, and whether it costs
anything. After it, say what it proved and what it did not (a mock run proves the wiring only; a green `weave check` proves the script is well formed, not that it is good).

Explain the golden dataset once, when you first write or show it, because a YAML file does not look like a test: each scenario is an example request plus checks.
Some checks are fixed and decided by code (`expected_output_contains`, `expected_output_not_contains` for something that must never appear, such as a card number,
`expected_min_words` / `expected_max_words` for a length, and `expected_tools`); others are lines a second model grades (`rubric`, `expect`), which are useful but not perfect. A mock run decides none of them (they come out
unjudged); a real run does, under a cap you agree first. Offer `weave graph` to look at the workflow whenever its shape changes.

When you open the eval4j dashboard, **read it before you describe it**: say what is actually on the screen (how many rows, that each row is named "<agent or workflow> · <example>", that every check in a mock run is the wiring check, and which quality dimensions have no results). Never describe a dashboard from this file's wording alone.

## Things that look like success and are not

- `Tests run: 0` in a Maven build. An old Surefire finds no JUnit 5 tests and says BUILD SUCCESS. The test setup `weave init`
  creates pins a working Surefire and fails an empty run; if you write a pom yourself, do the same.
- A mock run with all green. It proves the wiring only.
- An unjudged line in `weave eval`. Nothing confirmed it.
- The editor and `weave check` disagreeing. Trust `weave check`, and tell the user it is a bug in the editor.

## Be honest about the edges

Loom's responsibility ends at the workflow: its script, its prompts, its golden dataset, and what `weave check`, `weave audit` and `weave eval` verify. Tests for tasks, tools, an application's screen, or any other code a developer or an agent writes are still written (by the agent, as part of writing the code) with the project's own test framework and run by the project's own build (Maven, npm, and so on); `weave` does not run, provide a harness for, or check them.

llm4j does not (yet) provide: a cost estimator for Loom (keep a small cost model and compare it with the measured spend report); detection of
prompt injection (defence is architectural, so write hostile cases and assert on tools used and the trace); ready-made PII-leak or red-team
assertions in eval4j; or visibility into what Java or MCP tools, or your own tasks, do in `weave audit` (it reads the script only; review task
code like any code that moves money). `weave eval` writes its own HTML page and JSON; the richer eval4j report with the graph overlay needs the
Java path. Say so when it matters instead of improvising.

## Useful commands

```
weave next                                      # what to do next in this project, in order, free (a good first command in an existing project)
weave explain workflow.loom                     # the script in plain English: show it to the user to confirm it does what they meant
weave init pipeline my-workflow                 # a small, complete Maven project: script and prompts in src/main/resources, golden dataset in src/test/resources, JUnit tests, README
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
