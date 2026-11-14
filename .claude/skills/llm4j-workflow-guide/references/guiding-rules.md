# The guide's rules in full

`SKILL.md` holds the short form of every rule below and says when to read each one. This file holds the full text, unchanged. Read the rule
when you reach its stage or situation, not before: it is long on purpose.

## Rules for guiding a person through the stages

0d. **Bound a fan-out over a list a model wrote.** A `for each` over an agent's or extractor's list needs `max N` (LR13); a longer list fails the run before any item starts and names the planner. The bound is the last line of defence: tell the planner the limit in its instructions and add a golden case that its list stays within it (`weave capture` turns the failed run into the case).
0c. **A long agent keeps its progress in the workflow.** When a user describes one agent working through many steps, do not let it track "done" and "next" in its conversation: build the bounded loop with a worker (`memory { transcript: none }`) and a `Progress` extractor that writes a typed variable (starter 16).
0b. **Think first, then format.** An agent that has to reason (tools, web search, rounds, a long prompt) must not be given a large `output_schema` (a nested object, a list of objects, or more than five fields): "JSON only" leaves it no room to think. Let it answer in prose and add an `extractor` after it (`weave review` rule LP10, and `--fix-finding … --option split` writes the rewrite). A small flat schema is still right for an agent that does not reason; an answer that is itself a large artifact (source files) is accepted with a reason.
0a. **An extraction problem is not a conversation.** A step that only pulls fields out of text is an `extractor` (`extract Name from text -> var`), not an agent with an `output_schema`: the extractor is stateless, so it never sees the run's transcript or the shared prompt, and a test of it on its own predicts what it does inside the workflow. Declare the fields, list the ones the text may leave out in `optional:`, and branch on `== missing`. It is the sibling of "a classification problem is not a generation problem".
0. **Start from a template, not from an example.** For a new project run `weave init pipeline|approval|classifier|empty` (`empty` has nothing from a sample to delete; otherwise delete the sample's leftover agents, prompts and examples as soon as you rename the project, and tell the user which you removed and why) (pick the one nearest what the
   user described) and change it. Whatever you copy is a **reference to be modified, never a finished product**: say so to the user, and change its agents, prompts, tools, dataset and limits to fit their workflow. A large example app is a case study, not a starting point: copy a small template that already passes
   `weave check --no-env` and `weave eval --mock`.
   **Current information.** When an agent needs facts newer than its model, give it `grounding: web` (Perplexity, Claude, Gemini, or OpenAI and xAI through a provider with `api: responses`) rather than a search tool: its sources arrive in `{name_citations}`, `budget { searches: N }` caps it, `weave check` refuses a model or setting that cannot ground, and `weave audit` counts it as reading untrusted content (LA20), so keep it away from private data and from tools that send or act. Test with `ScriptedClient.sources(...)` and `citesOnlyFrom(...)`. A vendor is called supported only when its test passes on responses recorded from the real API; Kimi and Qwen are preview.
   **Pictures, sound and documents.** Send a file with a task: `delegate "…" to Vision attach image "{receipt}" -> total` (also `broadcast`, `handoff`, `human_prompt`; `extract R from image "{receipt}"`), with `media_root: "./assets"` naming the one folder files may come from. `weave check` refuses a model that cannot take the kind; an agent or extractor with a PII guard refuses media unless it says `media: unscanned`; `weave audit` flags a file chosen at run time (LA23). `intentclassify` takes no media. Guide: "Sending pictures, recordings and documents".
   `weave init <template> <dir>` makes a **Maven project**. Right after it runs, tell the user in plain words what each part is for, using the paths the command
   printed: `src/main/resources/main.loom` is the workflow and `src/main/resources/prompts/` its prompts (they travel with the program);
   `src/test/resources/eval/golden/` is the **golden dataset, which is the eval tests**: example requests and what a good answer does;
   `src/test/java/starter/` are eval4j JUnit tests that run every scenario of that dataset as its own test case, on a model that costs nothing (`mvn test`);
   `pom.xml` builds it; `.env.example` is where a key goes (copy it to `.env`, which git ignores). The commands take the script's path:
   `weave check src/main/resources/main.loom --no-env`, and `weave next` from the project root says what to do next. Never copy files out of a scratch
   folder by hand, and never rearrange the layout: `weave init <template> --flat` makes the older flat folder if the user really wants one.
1. **Ask once: "Do you want tests first?"** (default yes). The golden dataset is the requirements: what the workflow must do and must never do, as examples. Internal nodes have cases of their own and `weave check` requires them: an extractor needs a case that names fields (`expected_fields`), an evaluator needs, for every rule of its policy, a case that breaks it and a clean one (a near miss is best). Explain this to the user before the check fires, and write those cases with them. Skipping is allowed, but it is loud: `weave run` and `weave deploy` print "No evaluation", the live page shows a banner, and `weave review` reports LT06. If yes, the order is: decide the agents, write the golden dataset (the project from `weave init` comes first: `weave eval --check` needs a script and the folders, so run `weave init`, then write the examples, then change the script; red checks before the script exists are expected, and say so)
   (`weave eval <script> --init`, then fill it in with the user), write the script, `weave eval <script> --check`, `--mock`, then a capped
   real run. **Write the dataset before the script, and write it WITH the user, not for them**: read `weave guide 11` and run that conversation (teach in plain words, ask five short questions per agent, collect their real example requests, show samples and ask good-or-bad, read the list back, only then write the file), and **never write a Java loader or a test module for the dataset**: the project
   from `weave init` already has the JUnit tests that run it (`mvn test`), and `weave eval` reads the same YAML. If no, skip stages 2, 3, 4, 5 and 8 and go straight to the script, `weave check`, `weave audit` and a
   capped run. Record the answer in the project's README (`Evaluation: skipped` or `Evaluation: golden dataset in <its folder>`) and do not ask
   again. At go-live run `weave eval <script> --requirements --format html`, show the page to the user and ask for their sign-off (`--approve --approver <name> --why "<text>"`) before going live. If evaluation was skipped, say once, in one sentence, that no evaluation exists, and carry on if the user still wants to go live. Skipping never
   loosens a cap, an approval or a guard.
2. **Two paths.** (Skip this question when a template already settled it: a project from `weave init` is the script path.) Ask whether the workflow is a script run with `weave` (Loom is the runtime; you write no Java for the workflow itself) or Java code (agents built in Java,
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
   wiring, not the quality (and a code step that changes things is described, not run; approvals are answered yes): content checks show as *unjudged*, and unjudged is never a pass.
6. **Before any real run, confirm:** provider-side spending limits are set, the cap is below them, the key is in `.env` (developer) or the secret store (deployer), and the
   free run is green. Ask; do not assume. Never run a paid stage the user has not agreed to.
   **Before the first paid evaluation, also say who grades the plain-sentence checks and with which key**: unless `--judge <model>` names another, the agent's own model grades its own answers, with the same key, and those calls are part of the cost. Ask "should the same model grade, or a different one?", name the key the choice needs, and read the `Judge:` line `weave eval` prints before it asks to spend.
7. **Keys: two different people, two different answers. Ask which one this is.** Ask it in these words before giving any key advice: "Will you run this yourself on this laptop with `weave`, or is it a program or server that will use it?" Then show both routes side by side and let the user choose** (the project's README has both): `.env` for `weave` on their own machine; `weave secrets create`, `weave secrets set NAME`, then `--secrets <file>` on `weave run` and `weave eval` for a program or server. Never offer only one. **Never put a key in `.env.example`** (it is committed). When you rewrite the project's README, keep its key section (both routes and the judge note).
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
      (`requiresApproval`, and `approve: [Name]` in the script). When a person must say yes before an action, teach the three shapes: `use Tool(...) approve` or `run Task(...) approve` for a plain yes or no, asked once at the edge about the exact payload (the agent proposes, code acts); `human_prompt` then `alt` when the person shapes the answer; `decide` with a ladder only when trust should be earned (optional). `approve:` on an agent is for an assistant a person works with as it goes. Also check the hand-off into every action: an extractor, a check, or `approve` between one agent's free text and the step that acts on it (review rule LR11).
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

12. **Know where the project stands, and show what you built.** After `weave explain`, read its plain-English text back to the user and ask whether it is what they meant, before you build on it. In a project that already has files, run `weave next` first: it says what to do next, in order, and
    it is free. After you change a script, run `weave explain <script>` and read the plain-English description back to the user, so they can confirm it is what they
    meant before anything is run. Neither command calls a model. When the user asks how to change something, look in `weave guide recipes` for a tested pattern.

13. **Do this as soon as any agent uses a built-in tool, not at the end** (it is easy to skip: a `shell` tool counts). If any agent uses a built-in tool (`webhook`, `email`, `http`, `file`, `shell`, `sql`, `workspace`, `sandbox`), prepare it for deployment.** Write a "Deploying" section in the project's README with one line per name the
    script needs (get them from `weave check <entry> --no-env`, "not set yet"): what it is for and how the deployer sets it. Then, from chapter 9 ("Built-in tools when deployed"):
    - keep a **development entry** (email `outbox:` instead of SMTP, a test webhook URL) and a **deployed entry** that import the same workflows; tools are declared in the entry file;
    - every credential, webhook URL and SMTP password is `env.NAME` or `secret.NAME` and is supplied by the service's environment, the secret store or a vault host, **never the project's `.env`**;
    - settle approvals for a workflow nobody watches (`approve:` needs `--ask-via` or a host `HumanInterface`; a `shell` tool needs `approve:` or `unattended: true`), a durable `--journal`, `max_per_run` for
      email, `allow_paths` and `methods` for `http`, a writable persistent folder for `file` and `workspace`, installed programs for `shell`, Docker or Podman and the image for a `sandbox` with `isolation: container` (a missing runtime stops the run, nothing falls back), and a **read-only database user** for `sql`;
    - tell the user to run `weave check <deployed entry>` (without `--no-env`) on the target machine, then `weave audit --fail-on medium`, then a capped smoke run to a test sink before the real recipients.

14. **Round up before you talk about money.** When the workflow is built and the free checks pass, finish the job in this order. Do not end on "the next step costs money".
    a. **Build it.** Run `mvn test` (the project's own build, **without `-q`** so the totals show) and say the result in one line ("13 tests, 0 failures"). `Tests run: 0` is a failure.
    b. **Write `PROJECT_REPORT.md`** at the project root, from the template below, and tell the user where it is. Take the facts from the tools, not from memory:
       `weave explain <script>` for the steps, `weave graph <script> --format mermaid` for the picture, `weave audit <script>` for the safety summary.
    c. **Open the eval4j dashboard.** `mvn test` wrote `target/eval4j/report/index.html`. Open it for the user in their browser (`open` on macOS, `xdg-open` on Linux,
       `start` on Windows); if you cannot open a window, give the full path and say so. Tell them what they are looking at: **Wiring 100%** means every example ran through
       the workflow end to end on a fake model; **Safety** and **Tone** (the dataset's quality dimensions) read "Declared, not evaluated" because only a real, capped run judges
       them; the large percentage at the top is the wiring only, not the quality. Offer `weave graph` or the editor's **Loom: Show Workflow Graph** to look at the workflow itself. **There are two reports, and users mix them up: say which is which.** The dashboard from `mvn test` (`target/eval4j/report/index.html`) shows the free wiring checks with **one card per agent** under "Agents" (and one for each workflow under "Workflows"), each row named "<agent or workflow> · <example>"; an agent with no examples still has its card, marked "not evaluated", which is your cue to write some (`weave guide 11`). Look at the cards before you describe them. The page from `weave eval <script> --agent <Name> --report page.html` (or `--report` for everything on one page) lists, for one agent or the whole workflow, each example and each check on it. If the user asks for "the eval report per agent", that is the second one: run it for each agent and open them. If a number looks too high after you changed a check, run `mvn clean test` (old results of a renamed check are carried over and the summary says how many).
    c2. **Update the project's `README.md`** so it matches what now exists (the agents, the checks, the commands, where the report is) and ticks off the stages done.
    d. **Then, last, how to set up and run it for real.** Write it in the report and say it in chat, in this order, and run none of it without a yes: what a run costs and what stops
       it (the script's `budget`, `--max-tokens`, the provider's own spending limit); getting a key from the provider; once the user has chosen the route (step 7), set it up: for `.env`, ask first, then run `cp .env.example .env` (it only copies a template; do not open the result) and have the user type the key after the
       `=`; for the secret store, give them `weave secrets create` and `weave secrets set NAME` to run themselves (it asks for the value without showing it). never ask for it in chat, never print `.env`; `weave check <script>` without `--no-env`; one capped run (`weave run <script> -i name="..." --max-tokens 50000`);
       tell them who grades (see step 6) and then the capped real evaluation (`weave eval <script> --max-tokens 200000 --report target/eval-report.html`, which says what it will do and asks first); and, when they are happy,
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


## What a real workflow needs that a first draft forgets

**1. A plan for the context window (chapter 14, `weave guide 14`).** An agent that loops over tools adds to its prompt on every step, and the whole prompt is sent again each time: it ends in a "prompt too long" error, or in a cost that climbs during one run. Ask, per agent, whether it loops on tools. If it does, write a `context { compact: chain(trim_tool_results, drop_oldest) ... }` block with the user (add `summarize` only when the old steps carry decisions the agent still needs, and tell the user it is a model call that costs money, with a `summarizer` model). The size of a model's window is **not** written in the script: it is a fact about the model, in a windows file (`<script>.windows`), the catalogue that ships with the jar, or the server's catalogue; never invent a number, ask the user or read the provider's documentation. `weave check` rejects a bad block and warns when no window is known; `weave audit` shows each agent's strategy and window. A short run (a few steps, no tool loop) needs none of this: say so and leave it out.

**2. A decision for every step that can fail (chapter 6).** A `run` task or a `delegate` can fail. For each one whose failure matters, decide with the user: `retry N` (only for a task that is idempotent), `on_failure { ... }` (a note, a person, another path), or let the run fail on purpose. A step with no `on_failure` ends the run as failed, and the run names the step (`failed_kind`, `failed_name`, `failed_step`, `failed_category`).

**2b. Where a variable is replaced (chapter 6).** Only `{name}` and `{name.field}` are replaced; a plain word stays a word. In a workflow test, `real: [TaskName]` runs a task's own code when it declares no effects; a task that changes things stays scripted in `tasks:`.

**2a. Tone and background every agent needs, and rules that must hold (chapter 6).** If the same tone, house style or background is pasted into more than one agent, move it to one top-level `shared_prompt: "…"` (or `shared_prompt: prompt "house-rules"`); keep it stable, with no dates or run ids. Rules about what must never be said do **not** go there: a prompt is a wish, not a guarantee. During agent design ask, for each agent, "do its words reach a person?" Those agents get tone and task only. The rules go in a policy file under `policies/`, written with the person who owns compliance, and a `check <reply> against <Policy> using <Evaluator>` stands before every exit. Internal work (labels, fields, judging) goes in internal nodes: `intentclassifier`, `extractor`, `evaluator`. Review `weave check`'s sink errors with the user: each names the line of an exit and of the agent that wrote the text. At stage 7 (validate and audit) name LY07 and the leak guard (`guard { prompt_leak: warn }`), LH05 (an agent asks part-way through its work in a scheduled workflow: approve once at the edge instead), LR11 (an agent acts on another's unchecked text), and the count of questions a run can put to people (LH03). When a `delegate` is sent to an agent a model named (`to {task.owner}`), give it an allow-list (`in [A, B]`) or an `enum` in the schema that wrote the value, and store answers as `results[task.name]`; `weave audit` names a routing choice nothing bounds as LA22.

**2b. Who may step in, and what a stop means (chapter 15, `weave guide 15`).** Ask: will a person watch this run and possibly correct it? Can an agent loop, run long, or spend a lot? If yes, walk the user through the four tools and pick the smallest that fits: **steer** corrects an agent's reasoning and never alters the workflow; **wrap up** makes it finish now with what it has; **cancel** stops the step and something decides what happens next; **cancel, sent back** returns the run to a declared checkpoint carrying the person's reason (the input to the step was wrong). Then, with the user:

- Mark each agent a person may steer `steerable: true` with `streaming: true` (and `reasoning: true` if they want to see its thinking). It is off by default; every steerable agent shows up in `weave audit` as an input channel (`LA18`), so ask who may steer and keep a `guard` on it.
- For each step whose stop matters, write `on_stopped { ... }` (outcomes `use partial`, `skip`, `discard`, `retry`, `suspend`, `conclude`, `ask`, or `rewind to <declared checkpoint> at most N times carrying feedback = "{_stop.note}"`) or set script-wide `intervention { timeout: retry  sibling: discard }`. Only offer `retry` for an agent that only reads, or test `_stop.changed`. With nothing written, a person's stop is put to a person, and if nobody can be asked the run is suspended.
- For an agent that can loop or burn context, offer `supervise Agent { when looping(3) { steer "…" once } when tool_failing(Tool, 3) { wrap_up } when context > 90% { wrap_up } when steps > 15 { cancel use partial } }`: the rules act on counts of what the agent did, not on a model's opinion.
- Say how a person reaches the running process, because a suspended or finished run cannot be steered: `weave run --steer-via console`, a chat through `weave daemon --ask-via ...` for the people on the allowlist, or `LoomRun` in their own application. Fabric only shows these events; it has no control for them.
- In Java: `RunHandle` (steer, pause, resume, cancel, wrap up), `RunHook` (check the question, each model call, the final answer), `ToolHook`, and `DelegateTaskTool` (depth limit, tool grants) are in chapter 12.

**3. A security decision for every agent that reads, knows and can send (chapter 7).** `weave audit` marks an agent that can read untrusted content, reach private data and send something out (the *lethal trifecta*). Do not decide this for the user: either split the agent (one reads, one acts, with `approve:` on the one that acts) or the user accepts it **in writing, with a reason that names who looked and when**, in `<script>.inspect` (`trifecta: accepted "..."`). Tools declare what they do (`effect: none | reads | changes` in the script, per operation with `effects: { op: reads }` for OpenAPI and MCP; `reach: reads` and `X.reach = reads` in the `.loot` file still work for class tools) so the audit and a watched run agree, and a tool left undeclared is assumed (see the effect shown by `weave explain`). Never accept it yourself, and never accept it for `*`.


## When an agent should earn the right to decide (earned autonomy, optional)

This is optional and most workflows do not need it: raise it only when an agent will take over a decision a person makes today, and never add it unasked. Chapter 13 (`weave guide 13`) is the step-by-step; this section is the summary.

If the user wants an agent to make a decision that today a person makes (approve a refund, post a reply), do not give it the power
at once. Declare a `decision` and let it earn the freedom; the full language is in `LOOM_GUIDE.md` (section "Earned autonomy"), so read it before writing one.

- The agent **proposes**, a person **decides**, and a ledger of both moves the agent up a ladder. The three modes are `blind` (people
  decide without seeing the proposal), `confirm` (people accept, edit or reject it) and `autonomous` (the proposal takes effect; a sample is still checked).
- **The user writes the policy; you do not choose it for them.** Ask what evidence would make them trust the agent (how many cases,
  how long, how often it must agree, which mistakes are dangerous, what makes it slip back) and write that as a `ladder` with
  `climb when`, `slip [to rung] when`, `sample P% blind`, `escalate when` and `measure`s over `history`. The short form is a `trust { }` block, which Loom runs as a three-rung ladder. An agent can name a default with `ladder:` and `ceiling:`; a decision's own ladder wins.
- Every agent can reach autonomy: the default `ceiling` is the top rung. Do not lower it unless the user says the decision must always have a person.
- Say plainly that **the agent cannot see or change its own level or the ledger**; never put them in a prompt or a tool.
- Before it goes live: `weave ladder describe` (the policy in plain words; show it to the user), `weave ladder test` (try it on made-up
  histories, free), and `weave replay` or `test it on past cases` for a changed prompt or model. At run time: `weave autonomy status|history|promote|demote|freeze|outcome`.
- `weave autonomy verify <store>` checks the level record was not edited outside Loom. If it says it does not verify, tell the user and
  do not use `--accept` on your own: that is their decision, with a reason.
- Large proposals (a script, a document) are kept by reference in the ledger; `proposes: Name` is for a decision that writes something.
- An untried ladder is not trusted. State in the report that no real case has run, so no level has been earned yet.

