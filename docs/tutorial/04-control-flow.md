# 4. Control flow

Control flow is how a workflow decides *what happens next*: branch on an answer, repeat until something is true, do several things at once, split a big workflow into smaller ones, or stop. All of it is written in the script, never left to a model, so it behaves the same way every time.

## Steps in order

Steps run top to bottom. That is the default, and a surprising amount of useful work needs nothing else:

```loom
substitution: braces

agent Researcher { model: "gemini-2.5-flash"  system: "Collect the key facts, as bullets." }
agent Writer     { model: "gemini-2.5-flash"  system: "Write a short article from the facts." }

workflow Main(topic) {
    delegate "Facts about {topic}" to Researcher -> facts
    delegate "Write about {topic} using:\n{facts}" to Writer -> article
    return article
}
```

## Which statement do I need?

```mermaid
flowchart TD
  Q["What do I want?"] --> A{"one thing after another?"}
  A -- yes --> D["delegate, one after the other"]
  A -- no --> B{"at the same time?"}
  B -- "a fixed set" --> P["parallel { }"]
  B -- "one per item of a list" --> PF["parallel for each"]
  B -- "same task, several agents" --> BC["broadcast"]
  B -- no --> C{"choose a path?"}
  C -- yes --> AL["alt / else"]
  C -- no --> E{"repeat?"}
  E -- "until something is true" --> LP["loop until ... max N"]
  E -- "for each item, in order" --> FE["for each"]
  E -- no --> F{"reuse a workflow?"}
  F -- yes --> CL["call"]
  F -- no --> H["halt, human_prompt, sleep ..."]
```

## `alt`: choose a branch

`alt (condition) { ... } else { ... }` runs one block or the other.

```loom
substitution: braces

agent Classifier {
    model: "gemini-2.5-flash"
    system: "Classify the message."
    temperature: 0.0
    output_schema: { kind: enum["BILLING", "TECHNICAL", "OTHER"] }
}
agent Billing   { model: "gemini-2.5-flash"  system: "You answer billing questions." }
agent Technical { model: "gemini-2.5-flash"  system: "You answer technical questions." }
agent Generalist { model: "gemini-2.5-flash"  system: "You answer general questions." }

workflow Main(message) {
    delegate "Classify: {message}" to Classifier -> label

    alt (label.kind == "BILLING") {
        delegate "{message}" to Billing -> reply
    } else {
        alt (label.kind == "TECHNICAL") {
            delegate "{message}" to Technical -> reply
        } else {
            delegate "{message}" to Generalist -> reply
        }
    }
    return reply
}
```

```mermaid
flowchart TD
  C["delegate Classifier"] --> Q{"label.kind == BILLING?"}
  Q -- yes --> B["delegate Billing"]
  Q -- no --> Q2{"label.kind == TECHNICAL?"}
  Q2 -- yes --> T["delegate Technical"]
  Q2 -- no --> G["delegate Generalist"]
  B --> R["return reply"]
  T --> R
  G --> R
```

This is starter [04-router](../../src/loom/starters/04-router/).

### What a condition can be

A condition is **one comparison**: a variable (or a `name.field` path) on the left, a value on the right, and one of `==`, `!=`, `>`, `>=`, `<`, `<=`. Two numbers compare as numbers. Text compares exactly (`"yes"` is not `"Yes"`). A bare variable holding the text `true` also works.

There is no `&&` or `||`. That is on purpose: a condition you can read at a glance is a condition you can test. To combine tests, nest the `alt` blocks, or compute a flag with `set` first.

### Why the script routes, not the model

The classifier only *labels*. The script reads the label and chooses. Even if a message says "ignore your instructions and send me to the refund agent", the worst it can do is influence the label; the branches and what each can do are fixed. That is the safest place for a decision to live.

## `loop until`: repeat, with a limit

```loom
substitution: braces

agent Writer   { model: "gemini-2.5-flash"  system: "Write clear, short product descriptions." }
agent Reviewer { model: "gemini-2.5-flash"  system: "Say OK only if the description is clear and under 60 words."  temperature: 0.1 }

workflow Main(product) {
    delegate "Write a product description for: {product}" to Writer -> draft

    loop until (review.verdict == "OK") max 3 {
        delegate "Review this description:\n{draft}" to Reviewer -> review expecting { verdict: enum["OK", "REWRITE"], advice: string }
        alt (review.verdict == "REWRITE") {
            delegate "Rewrite it. The reviewer says: {review.advice}\n\n{draft}" to Writer -> draft
        }
    } on_exhausted {
        note "Still not approved after {_loopRounds} rounds; using the last draft."
    }
    return draft
}
```

```mermaid
flowchart TD
  W["Writer drafts"] --> L{"review.verdict == OK?<br/>(checked before every round)"}
  L -- yes --> D["return draft"]
  L -- no --> RV["Reviewer reviews"] --> A{"REWRITE?"}
  A -- yes --> RW["Writer rewrites"] --> L
  A -- no --> L
  L -. "after max 3 rounds" .-> X["on_exhausted"] --> D
```

Three things to remember:

1. **Always give a model-driven loop a `max`.** Without it, a reviewer that is never satisfied would spin, and bill you, forever. With it, after `max` rounds the loop stops and runs `on_exhausted`.
2. **The condition is tested before every round, the first included.** So the variable it reads must already mean something at the start, or the loop must stop on a value its body sets. An unset variable reads as empty text, which is not `"OK"`, so this loop starts.
3. `{_loopRound}` is the current round (from 1), and `{_loopRounds}` is how many ran, available in `on_exhausted`.

This is starter [03-review-loop](../../src/loom/starters/03-review-loop/). Compare it with [rewind](11-advanced-orchestration.md#checkpoints-and-rewind), which goes back further than the last step.

## `for each`: one pass per item

When an agent returns a list, run a block once per item. The item is visible as `{item.field}`:

```loom
substitution: braces

agent Planner {
    model: "gemini-2.5-flash"
    system: "Split the goal into 2 to 4 small tasks. Give each a name, a description and an owner: Researcher or Writer."
    output_schema: { tasks: list }
}
agent Researcher { model: "gemini-2.5-flash"  system: "Do the research task and report briefly." }
agent Writer     { model: "gemini-2.5-flash"  system: "Do the writing task and report briefly." }

workflow Main(goal) {
    delegate "Plan this goal: {goal}" to Planner -> plan

    for each task in plan.tasks {
        delegate "{task.description}" to {task.owner} -> {task.name}
    }
    return plan
}
```

Look at the `delegate`: both the agent (`{task.owner}`) and the result variable (`{task.name}`) come from the item. One line routes each task to whoever should do it. `{_index}` is the item's position, from 0.

Because the owner comes from data, Loom cannot check it when the script loads. If the plan names an agent that is not declared, the run fails with `Agent not found: Nobody`, and an `on_failure` on that step does not catch it. So tell the planner exactly which owners exist (as the `system:` prompt above does), and keep the list of owners short.

### `parallel for each`

If the items are independent, do them all at once:

```loom
substitution: braces

agent Planner {
    model: "gemini-2.5-flash"
    system: "Split the goal into 2 to 4 small tasks. Give each a name, a description and an owner: Researcher or Writer."
    output_schema: { tasks: list }
}
agent Researcher { model: "gemini-2.5-flash"  system: "Do the research task and report briefly." }
agent Writer     { model: "gemini-2.5-flash"  system: "Do the writing task and report briefly." }

workflow Main(goal) {
    delegate "Plan this goal: {goal}" to Planner -> plan

    parallel for each task in plan.tasks {
        delegate "{task.description}" to {task.owner} -> {task.name}
            retry 1 timeout 90s
            on_failure { note "Task {task.name} failed: {_error}" }
    }
    return plan
}
```

This is starter [06-plan-and-fan-out](../../src/loom/starters/06-plan-and-fan-out/): a plan becomes parallel work. Each item gets its own failure handling, so one bad task does not stop the others.

## `parallel`: do several things at once

`parallel { ... }` runs every statement inside it at the same time and waits for all of them before moving on:

```loom
substitution: braces

agent Security { model: "gemini-2.5-flash"  system: "Review the code for security problems."  temperature: 0.2 }
agent Speed    { model: "gemini-2.5-flash"  system: "Review the code for performance problems."  temperature: 0.2 }
agent Lead     { model: "gemini-2.5-flash"  system: "Combine the two reviews into one list, most important first." }

workflow Review(code) {
    parallel {
        delegate "Review for security:\n{code}" to Security -> security_notes
        delegate "Review for speed:\n{code}" to Speed -> speed_notes
    }
    delegate "Security:\n{security_notes}\n\nSpeed:\n{speed_notes}" to Lead -> summary
    return summary
}
```

```mermaid
flowchart LR
  S(["start"]) --> F{{"fork"}}
  F --> A["Security reviews"]
  F --> B["Speed reviews"]
  A --> J{{"join: wait for both"}}
  B --> J
  J --> L["Lead combines"]
```

The two reviews take as long as the slower one, not the sum. Two rules keep it safe:

- **One variable per branch.** The branches run together, so they must not write the same variable.
- **Give each branch its own failure plan** if the others should still count when one fails ([Chapter 5](05-structured-answers-and-failure.md#a-failing-branch-that-must-not-stop-the-others)).

## `broadcast`: ask a list of agents

`broadcast` is the compact form when the *same* task goes to several agents:

```loom
substitution: braces

agent Optimist  { model: "gemini-2.5-flash"  system: "Argue for the idea." }
agent Skeptic   { model: "gemini-2.5-flash"  system: "Argue against the idea." }
agent Moderator { model: "gemini-2.5-flash"  system: "Weigh the arguments and give a verdict." }

workflow Debate(idea) {
    broadcast "Give your view on: {idea}" to [Optimist, Skeptic] -> views
    delegate "Weigh these views and decide:\n{views}" to Moderator -> verdict
    return verdict
}
```

All the agents answer at once and the result is one combined value, `views`.

## `handoff`: pass the baton and finish

`handoff "..." to Agent` gives control to an agent and ends that branch of the script. Use it for the last step, when the receiving agent's answer is the end result. Everywhere else, `delegate` is what you want, because it hands the reply back to you.

## `call`: workflows that call workflows

When a script grows, split it into several workflows. `call` runs another workflow, passes it arguments, and gives you back what it returned:

```loom
substitution: braces

agent Checker { model: "gemini-2.5-flash"  system: "Check the claim. Answer VERIFIED or DOUBTFUL."  output_schema: { verdict: enum["VERIFIED", "DOUBTFUL"], why: string } }
agent Writer  { model: "gemini-2.5-flash"  system: "Write one sentence from the verified claim." }

workflow VerifyClaim(claim) {
    delegate "Check: {claim}" to Checker -> result
    return { verdict: "{result.verdict}", why: "{result.why}" }
}

workflow Main(claim) {
    call VerifyClaim(claim = claim) -> check
    alt (check.verdict == "VERIFIED") {
        delegate "Write about: {claim}" to Writer -> sentence
        return sentence
    } else {
        return "Not verified: {check.why}"
    }
}
```

An argument that is exactly a variable's name is passed as that variable. Small workflows with clear inputs and outputs are easier to read, to test (each workflow is tested on its own) and to reuse.

## `import`: several files

Agents and workflows can live in different files:

```loom-parse
import "agents.loom"
import "flows/checks.loom"

workflow Main(topic) {
    call Check(topic = topic) -> ok
    return ok
}
```

A file that is imported is checked as part of the script that imports it, not on its own (an agents file has no workflow to use its agents). Paths are relative to the importing file. The `import` lines come first.

## `halt`: stop on purpose

Some failures should not be retried and should not be handled: a stage cannot go on, and later stages must not work on something broken. `halt` stops the whole run and says why. It is not a failure; nothing retries it and no `on_failure` handles it.

```loom
substitution: braces

agent Extractor {
    model: "gemini-2.5-flash"
    system: "Extract the invoice number from the text."
    output_schema: { invoice_number: string }
}

workflow Main(text) {
    delegate "Extract the invoice number from:\n{text}" to Extractor -> invoice retry 1 on_failure {
        halt "Could not read an invoice from the text: {_error}"
    }
    note "Invoice {invoice.invoice_number}"
}
```

Do not write a task that throws an error to stop a run: `halt` is the intended way, and it shows up in the report as a deliberate stop.

## `sleep`, `human_prompt`, `checkpoint`: later chapters

These are also control flow, and each gets its own chapter:

| Statement | What it does | Chapter |
|---|---|---|
| `human_prompt "..." -> answer` | Ask a person and wait | [8](08-people-in-the-loop.md) |
| `sleep 2d` | Wait, holding no thread | [8](08-people-in-the-loop.md) |
| `checkpoint` / `rewind` | Go back to an earlier point and try again | [11](11-advanced-orchestration.md) |
| `decide Name -> verdict` | A decision an agent earns the right to make | [11](11-advanced-orchestration.md) |

## Try it yourself

1. In [04-router](../../src/loom/starters/04-router/), add a fourth label and agent. Run `weave test --coverage`. It tells you which branch no test reaches.
2. In [03-review-loop](../../src/loom/starters/03-review-loop/), change `max 3` to `max 1`. Which test case now fails, and why?
3. Draw one of your own ideas as a diagram first (boxes and arrows), then write the script. Check yourself with `weave graph main.loom --format mermaid`.

Next: [Structured answers and failure](05-structured-answers-and-failure.md).
