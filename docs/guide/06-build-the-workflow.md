# 6. Build the workflow

**Goal:** compose the agents you have already tested into a Loom workflow whose every step is named, traced and budgeted.

## Why Loom, why now

Building the workflow *after* the agents are tested means a failure is the workflow's fault, not an untested prompt's. In Loom each step is a
named delegation with a trace event, a budget and a replay point, which is exactly what chapter 8's trajectory tests assert on.
Reference: [Loom guide](https://github.com/srijithunni7182/llm4j/blob/main/loom/ai-agent4j-loom/LOOM_GUIDE.md).

## Order of work

If you chose to have tests (the README's first question), the dataset comes **before** this script: decide the agents (chapter 1), write
the dataset (chapter 2), write the script here, then run `weave eval <script> --check` and `weave eval <script> --mock`. If you chose to
skip evaluation, write the script now and go to `weave check` (chapter 7). Either way, a script-only project needs no Java loader or test
module: `weave eval` reads the YAML itself.

## Build it in this order

1. **Personas and agents**, copied from chapter 1 (the tested prompts and temperatures, unchanged).
2. **Tools** declared once; secrets only from `env.NAME` (a literal credential is a load error).
3. **A run budget** at the top. Start with a ceiling you can afford, then tighten it from measured cost.
4. **Tasks for the exact parts.** Anything chapter 1 marked as a task (rules, calculations, payments, audit writes) is Java registered as a `Task` and run with `run`, not delegated to a model (see below).
5. **The workflow**: parallel rounds, a branch for the case that should end early, typed hand-offs, a final consensus.

```loom
audit  { logger: "file"  path: "hexamind-audit.json" }
budget { tokens: 900000  calls: 220  warn_at: 80% }
tool Search { use: serpapi  api_key: env.SERPAPI_KEY }

agent Rahul { model: "gemini-3.5-flash"  persona: AdversarialResearcher  temperature: 0.2  max_iterations: 12  tools: [Search] }
agent Moderator { model: "gemini-3.5-flash"  system: "You read the first-round analyses and report whether any expert said a term could not be verified."  temperature: 0.0 }

workflow Collaborate(problem) {
    parallel {                                                       // round 1: every agent searches independently
        delegate "{problem} ... verify literally ..." to Alex  -> a1
        delegate "{problem} ... verify literally ..." to Rahul -> h1
        // ... one line per agent
    }
    checkpoint round1 starting with fb = "none"
    delegate "Problem: {problem}\nAlex: {a1}\nRahul: {h1} ..." to Moderator -> fabcheck
        expecting { fabricated: enum["YES","NO"], term: string }     // a typed hand-off: not free text
    alt (fabcheck.fabricated == "YES") {
        delegate "Round 1 findings: ... State the debunk." to Debunker -> final_text     // skip the debate about nothing
    } else {
        // rounds 2 to 5 in parallel, then:
        delegate "Synthesize into one recommendation ..." to Coordinator -> final_text
    }
    handoff final_text to Coordinator
}
```

Full script: [`hexamind.loom`](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/hexamind.loom).

## Tasks: the steps with no model

Hexamind has no step that needs one, but most business workflows do. A support bot that refunds orders lets a model read the message and lets **code** decide and pay:

```loom
workflow Refund(msg) {
    delegate "Extract the order id and amount from: {msg}" to Intake -> request      // the only step with a model

    run RefundPolicy(order = request.order_id, amount = request.amount) -> verdict   // plain Java: free, exact, testable

    alt (verdict.outcome == "approved") {
        run IssueRefund(order = request.order_id, amount = request.amount) -> receipt
            on_failure { human_prompt "Refund for {request.order_id} could not be confirmed: {_error}. Check the provider, then answer done." -> checked }
    } else {
        human_prompt "Refund refused: {verdict.reason}. Override? (yes/no)" -> decision
    }
}
```

- Write each task as a `Task` (`Task.pure(...)` for a rule, `Task.changes(...)` for a payment, with an `EffectPolicy` saying whether the provider deduplicates by the idempotency key) and register it with
  `executor.setTaskRegistry(...)` or `META-INF/services/io.github.llm4j.agent.task.Task`. (The agent writes unit tests for the task too, in the project's own framework, run by its own build; not with `weave`.)
- A task spends no tokens and does not count against the run budget; a task that changes things is **never repeated by a crash or a `retry`** unless it is idempotent.
- Reference: [Loom guide, Tasks](https://github.com/srijithunni7182/llm4j/blob/main/loom/ai-agent4j-loom/LOOM_GUIDE.md#tasks-deterministic-steps-run).

## When something is missing: write it, do not leave a gap

If the workflow needs a capability no built-in tool has, or a step that must always happen, **the agent building it writes that code** (you review it); it never leaves a
placeholder or asks a model to pretend. There are two kinds, and the choice matters:

| It is... | Write a | Because |
|---|---|---|
| Something an agent may *choose* to call, to look something up or compute | **custom tool** (`Tool` + a `.loot` file) | the model decides whether and when |
| Something that **must** happen every time (a rule, a calculation, a record, a payment, a notification) | **task** (`Task` + a `run` step) | the script decides; no prompt can skip, repeat or change it |

**Rule: a mandatory activity is a task run with `run`, never an agent and never a tool.** The only mandatory step that is not a task is asking a person (`human_prompt`, an
approval), because that is what the human interface is for. If you find yourself writing "the agent must always call X", it is a task.

A custom tool and a task, complete (these blocks are compiled and run by the repository's tests, so they are not pseudo-code):

```java file=shop/WordCount.java
package shop;

import io.github.llm4j.agent.Tool;
import java.util.Map;

/** A tool an agent may call: counts the words in a text. */
public class WordCount implements Tool {
    @Override public String getName() { return "word_count"; }
    @Override public String getDescription() { return "Counts the words in the text argument."; }
    @Override public String execute(Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", "")).strip();
        return String.valueOf(text.isEmpty() ? 0 : text.split("\\s+").length);
    }
}
```

```java file=shop/Slugify.java
package shop;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.util.Locale;

/** A task the workflow must run: turns a title into a url slug. Same input, same output, free, and no prompt can change it. */
public class Slugify implements Task {
    @Override public String getName() { return "Slugify"; }
    @Override public TaskEffect effect() { return TaskEffect.NONE; }   // NONE: pure; READS: only looks; CHANGES (the default): acts on the world
    @Override public TaskResult run(TaskContext context) {
        String title = context.requireArg("title", String.class);
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? TaskResult.rejected("the title has nothing to make a slug from") : TaskResult.value(slug);
    }
}
```

The files that connect them (a `.loot` line maps the name the script uses to the class, and an optional `Name.reach` line tells `weave audit` what the tool reaches: `none`, `reads`, `fetches`, `writes` or `sends`; the services file lists tasks, one class per line):

```text file=tools.loot
WordCounter = shop.WordCount
WordCounter.reach = none
```

```text file=META-INF/services/io.github.llm4j.agent.task.Task
shop.Slugify
```

```loom file=main.loom
agent Writer { model: "gemini-2.5-flash"  tools: [WordCounter] }

workflow Main(title) {
    run Slugify(title = title) -> slug_result
    alt (slug_result.outcome == "ok") {
        note "slug: {slug_result.value}"
    } else {
        note "cannot publish: {slug_result.reason}"
    }
}
```

Compile against the `weave` jar and run with the compiled classes beside it (the tool and the task have no other dependencies; `weave` alone cannot see your classes):

```bash
javac -cp weave.jar -d classes shop/*.java        # put META-INF/services/... under classes/ too
java -cp weave.jar:classes io.github.llm4j.loom.cli.WeaveCLI check main.loom --loot tools.loot --no-env
java -cp weave.jar:classes io.github.llm4j.loom.cli.WeaveCLI run main.loom --loot tools.loot -i title="Hello, World! 2026"
```

In a Maven project the same classes go in `src/main/java` and the services file in `src/main/resources`; run with the project's classes on the class path as above, or
`weave package` to bundle them. Rules for what the agent writes:

- **One class, one job, no secrets in code.** Keys come from the secret store; a tool that needs one takes it from its configuration, never a literal. Validate arguments and say what was wrong
  (a tool returns the explanation as text; a task throws `TaskNotPerformed` or returns `rejected(...)`).
- **A tool that acts on the world** (sends, writes, pays) overrides `requiresApproval(args)` to ask a person, and is listed in the agent's `approve: [Name]` in the script. If it must always happen, it is not a tool: make it a task.
- **A task that changes things** says so (`Task.changes(...)` with an `EffectPolicy`, or the default `CHANGES`) and uses `ctx.idempotencyKey()` with the receiver, so a resumed run does not do it twice.
- **What Loom checks is the connection**: `weave check ... --loot ...` with the classes on the class path names a tool or task that cannot be found. Tests for tasks, tools, an application's screen, or any other code a developer or an agent writes are still written (by the agent, as part of writing the code) with the project's own test framework and run by the project's own build (Maven, npm, and so on); `weave` does not run, provide a harness for, or check them. Write them: given this input, this output, including the refusal.
- **Name result variables so they cannot be ordinary words in your text** (`slug_result`, not `slug`): a variable name is replaced everywhere in a string.

## When the script gets long: split it into files

A script past roughly **150 lines**, or one that mixes several concerns, is hard to read and harder to review, however good the graph view is. Split it **before** it
becomes a wall, and tell the user how you split it. Loom composes files with `import` (brings in agents and workflows) and `call` (runs a workflow from another file).

How to cut, in this order:

1. **Entry file, short (under about 60 lines): imports, `budget`, `audit`, `rate_limits`, schedules, and the top-level workflow that reads like a table of contents** (one line per
   phase). Someone opening it should see the whole story in one screen.
2. **Agents in their own file** (`agents/team.loom`), one file per team if there are many. An agent is a definition; nothing runs when it is imported.
3. **One file per phase or reusable sub-workflow** (`flows/review.loom`), named after what it does. A sub-workflow of more than about 40 lines is a candidate for its own file.
4. **Prompts stay in `prompts/` files, tasks and tools stay in Java** (they were never in the script's way), so the scripts hold only the shape of the work.

```loom file=split/main.loom
import "agents/team.loom"
import "flows/review.loom"

budget { tokens: 200000  calls: 30 }

workflow Main(topic) {
    delegate "Write a short post about {topic}" to Writer -> first_draft
    call ReviewLoop(draft_text = "{first_draft}") -> reviewed_post
    note "Ready: {reviewed_post}"
}
```

```loom file=split/agents/team.loom
agent Writer { model: "gemini-2.5-flash"  system: "You write short, plain posts."  temperature: 0.7 }
agent Editor { model: "gemini-2.5-flash"  system: "You review posts and say what to change."  temperature: 0.1 }
```

```loom file=split/flows/review.loom
// The review loop: the editor reviews, the writer rewrites, at most twice. Returns the final post as `result`.
workflow ReviewLoop(draft_text) {
    loop until (review.verdict == "OK") max 2 {
        delegate "Review this post:\n{draft_text}" to Editor -> review expecting { verdict: enum["OK", "REWRITE"], advice: string }
        alt (review.verdict == "REWRITE") {
            delegate "Rewrite the post. The editor says: {review.advice}\n\n{draft_text}" to Writer -> draft_text
        }
    }
    delegate "Return this post exactly as it is:\n{draft_text}" to Writer -> result
}
```

What to know before you cut (each of these has caught people out):

- **`call` passes text and returns `result`.** The arguments arrive as text (a map is flattened to text), and what the caller gets back is the variable named `result` in the called
  workflow, so make the last step of a sub-workflow the one that binds `-> result`. A sub-workflow has its own variables; it cannot see its caller's. `weave check` does not
  warn that `result` is "never used" for this reason.
- **One flat namespace.** Agent and workflow names are shared by every file that is imported; two files defining the same name do not fail, one silently wins. Give every
  agent and workflow a name that is unique across the project (`ReviewLoop`, not `Review`), and never copy an agent into a second file.
- **`budget`, `audit` and `rate_limits` go in the entry file only.** Imported files are merged first and the first one found wins, so a `budget` in an imported file would
  override the entry file's, silently.
- **Import paths are relative to the file that contains the `import`**, and a cycle (A imports B imports A) is refused.
- **Check the split like any script**: `weave check main.loom --no-env`, and `weave graph main.loom` (the graph follows imports and draws a `call` as one step, the callee
  as its own workflow; in VS Code, **Loom: Show Workflow Graph** on the entry file). A golden dataset and `weave eval` work on the entry file as before.
- **Do not split just to split.** Two small files that always change together are one file. Split where a reader would want to open one thing and not the other.

## Things that bit us (so they do not bite you)

- **Variable names are replaced everywhere in a string, braces or not.** A workflow parameter called `consensus` turned "Previous consensus: {consensus}"
  into "Previous old: old". Name variables so they can never be an ordinary word in your prompts (`final_text`, `prior`, `feedback_text`).
- **A budget is a hard stop.** Exhausting it ends the run with an error and *no* output. If you want a partial answer, design a wind-down step or use
  `when_exhausted: suspend`.
- **`handoff` to an agent makes one more model call.** Count it in your cost model.
- **Give each branch its own agent** when you need to tell the paths apart in a trace: Hexamind added a `Debunker` so the debunk path differs from the consensus path.
- **Roles without tools** (moderator, coordinator) cannot be hijacked into acting; keep tools on the agents that need them.
- **An agent that can call a payment tool can be talked into it.** Put the payment in a task the *workflow* runs after code has checked the rules. A task is never offered to a model.
- **A green build that ran no tests is not green.** If Maven prints `Tests run: 0` and BUILD SUCCESS, an old Surefire found no JUnit 5 tests. `weave init <template> --with-java-tests` creates a test module that pins a working Surefire and fails a build that runs nothing; do the same in a pom you write.
- **Building llm4j from source? A stale jar runs old code.** After you change a module, install it (`mvn -DskipTests -Djacoco.skip=true install`; `-Djacoco.skip=true` is needed with `-DskipTests`), or the next module compiles and tests against the jar from before. The repository's `scripts/doctor.sh` compares each installed jar with its sources and prints the command to run. If you only use the `weave` jar this does not apply.

## Gate

`weave check your.loom` passes (next chapter), and the script uses the prompts and temperatures you tested, unchanged.
