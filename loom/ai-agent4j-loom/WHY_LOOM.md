# Why Loom?

**Loom is for agent workflows that run for hours, days or on a schedule: unattended, within a spending
limit, and able to wait for people and for rate limits without falling over.**

Most agent frameworks make the *model call* easy. The hard part of an autonomous workflow is everything
around it:

- a daily quota runs out at step 40 of 60;
- a person needs to approve step 12, and answers on Monday;
- the server restarts in the middle of the night;
- a critic loop never quite says "done";
- the bill for a runaway loop arrives at the end of the month.

Loom handles each of these in the language itself. Each takes a line or two of script, with no framework
code to write.

---

## The idea: the model reasons, the harness governs

A Loom workflow is a small script. The agents in it are LLMs; **everything between them is symbolic** and
run by the Loom runtime:

- who is called, in what order;
- what happens on failure;
- how many rounds a loop may take;
- what a step may spend;
- which tool calls need a person.

```text
budget { tokens: 100000 per day  when_exhausted: suspend }
rate_limits { on_limit: suspend  max_wait: 24h }

workflow Digest(topic) {
    delegate "Find today's news on {topic}" to Researcher -> findings expecting { items: list }

    for each item in findings.items {
        delegate "Summarise {item.url}" to Writer -> summary retry 2 backoff 2s timeout 90s
    }

    loop until (review.verdict == "OK") max 3 {
        delegate "Review the digest" to Critic -> review
    } on_exhausted {
        note "Publishing after {_loopRounds} rounds of review"
    }

    human_prompt "Publish today's digest? (yes/no)" -> go
    alt (go == "yes") { handoff "Publish" to Publisher }
}

schedule Morning { cron: "0 7 * * *"  timezone: "Asia/Kolkata"  run: Digest(topic="AI agents") }
```

A model can't talk its way past `max 3`, overspend the budget, or skip the approval. Those are not
instructions in a prompt; they are the program.

---

## What long-running, autonomous work needs, and how Loom provides it

### 1. Runs that survive anything

Every `delegate`, `broadcast`, `human_prompt` and approved tool call is recorded in a **run journal**:

- in memory, in a directory, or in any SQL database (`JdbcRunJournal`);
- re-running with the same journal **replays** finished steps, with no model call and no charge, then
  carries on;
- a process that crashed resumes from its last finished step, on any machine;
- the script doesn't change. Durability is a runtime setting (`--journal`), not a different way of
  writing the workflow.

### 2. Waiting costs nothing

- A `human_prompt` or an approval **suspends** the run: it throws `RunSuspended`, and no thread is held.
- The answer can arrive days later, on another server. Record it and resume the run.
- `approve: [Publish]` on an agent makes only those tool calls wait; everything else runs.

### 3. Rate limits and quotas pause the run instead of killing it

- ai-agent4j reads **when the limit resets** from each provider:
  - Gemini's `RetryInfo` and daily quotas;
  - Anthropic's and OpenAI's reset headers;
  - `Retry-After`.
- Short limits are waited out inline.
- For long ones, `rate_limits { on_limit: suspend }` makes Loom:
  1. pause the run;
  2. write a **resume trigger** for the reset time;
  3. carry on when the limit lifts, replaying everything already done.
- Parallel branches that can still work finish first, and the run pauses once.

### 4. Spending limits that can't be overrun

- **Scopes**: `budget { tokens: … calls: … cost: … }` caps a whole run, one agent (`per_call:` too), a
  single step, or a loop.
- **Checked before each call**: a call that can't be paid for is refused before it reaches the model, and
  each answer's `maxTokens` is lowered to what is left.
- **Refilling budgets**: `per day` (or minute or hour) budgets refill, and `when_exhausted: suspend` turns
  an empty allowance into a pause until it refills. This gives a background agent a daily allowance.
- **Routing on spend**: scripts can route on what is left, for example
  `alt (_budget.remaining < 20000) { … CheapWriter … }`.
- **Reports**: every run reports where its tokens went, and `weave run --max-tokens` caps any script from
  the command line.

### 5. Schedules without a platform

- `schedule` blocks run workflows on cron or an interval, in a time zone, from a trigger store (files or
  SQL) that survives restarts.
- Something has to wake Loom, and it doesn't need to be a server you keep running:
  - `weave triggers install <store> --apply` adds a cron line, systemd timer, launchd agent or Windows task
    that runs `weave tick`;
  - on Cloud Run, Cloud Scheduler calls a tick endpoint;
  - `weave daemon` keeps one process watching, if you prefer.

### 6. Loops and retries that can't run away

- `loop until … max N { } on_exhausted { }` bounds every loop; there is no unbounded form.
- `retry 2 backoff 2s timeout 90s` and `on_failure { }` put resilience next to the step it protects.
- `expecting { verdict: enum[...] }` gives a single step a typed contract, so conditions test typed values,
  not free text.

### 7. Data decides the routing

`for each fix in review.fixes { delegate "{fix.task}" to {fix.owner} -> {fix.output} }`: an agent's
structured answer decides which agent handles each item. `parallel for each` runs them all at once.

### 7b. Code where it must be code

Some steps must not have a model behind them. A support bot that refunds orders should let a model *read* the
customer's message, and let **code** decide whether the refund is allowed and call the payments API. In Loom
that code is a **task**: a unit of plain Java, run with `run RefundPolicy(amount = request.amount) -> verdict`.

- It costs no tokens and gives the same answer every time, so it is unit-testable like any function.
- It is immune to prompt injection: customer text only ever arrives as an argument *value*; it cannot select or
  change the code, and a model can never call a task as a tool.
- It is durable: journaled and replayed like any other step. A task that changes things (a payment) is never
  silently repeated after a crash. If the outcome is unknown, the run stops and tells an operator, unless the
  task is idempotent, in which case it runs again with the same idempotency key.
- It is checked before it runs: `weave check` rejects an unknown task, and a `retry` on a payment that could pay twice.

LangGraph has the same idea (nodes that are ordinary functions); Loom adds the guarantees around it
(a replayable journal, an effect protocol for the crash window, approvals, simulation, and a trace that shows
which steps had no model).

### 8. The whole agent, declared in the script

These are declared next to the agent that uses them:

- tools (SerpAPI, DuckDuckGo, any OpenAPI spec, your own classes);
- knowledge bases that retrieve;
- per-user memory and long-term facts;
- voice and translation in Indian languages;
- PII and bias guards;
- knowledge graphs, personas and remote skills.

Any Gemini, Sarvam, Ollama or Claude model works, and switching is a one-word change, because every
provider meets [one contract](../../ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md).

### 9. Checked before it runs, visible while it runs

- `weave check` reports every problem with its line before any model is called: an unknown agent or
  model, a missing key, a tool that isn't declared, an approval for a tool the agent doesn't have.
  Nothing in a script is silently ignored.
- `weave run --trace` streams every plan, tool call, observation, spend and pause as it happens
  (`--trace=json` for machines).
- Run results report tokens, calls and cost per agent. Exit codes say *done*, *failed*, *stopped by
  budget* or *paused*.

### 10. Agents that earn their autonomy

Every team asks the same question before an agent acts alone: *has it earned it?* Usually the answer is a
meeting before launch. Loom makes it a record.

- A **`decision`** is declared in the script: the agent *proposes*, a person *decides*, and Loom keeps a
  ledger of both. The agent starts at `watch` (nobody sees its proposal, so the measurement is honest),
  moves to `suggest`, and to `act` only when the record says so, on a conservative statistical bound (not a
  lucky streak) and, if you want, only with a named person's approval.
- It **falls back by itself** when the record turns: a dangerous mistake, a reversal or a drop in agreement
  moves it down, and `weave autonomy freeze` stops `act` for every case that starts after the command.
- A changed prompt, model or skill is a **new agent** and starts again, or is **tested on your past cases
  first**: `weave replay` runs the candidate over the ledger with nothing sent and nothing changed, and
  reports agreement, flips and the level it would earn.
- The rules read aloud: `to act: after 300 cases over 30 days, agreeing at least 95%, with no dangerous
  mistakes`. A reviewer, an auditor or a manager can sign that.

See [Earned Autonomy](LOOM_GUIDE.md#earned-autonomy) in the language guide.

### 11. A person in the loop who isn't at a desk

A run that needs a person suspends and holds no thread, so waiting costs nothing. Loom adds the missing half:
reaching the person and hearing back.

- With a channel configured for the run store (`--ask-via telegram`), every question (`human_prompt`, a tool
  approval, a held rewind, a promotion, a `decide`) arrives as a chat message with a short code; the reply
  records the answer and the run carries on at the next tick.
- Only people on an allowlist can answer; an approval needs the code in the reply; the message for a `watch`
  case never carries the agent's proposal, so the measurement stays honest.
- Nothing about it is in the script, so the same workflow asks at a console on a laptop and in a chat on a
  small server.

See [Answering from Your Phone](LOOM_GUIDE.md#answering-from-your-phone).

### 12. Ships like any Java application

- `weave package --fat` builds one runnable JAR.
- Embedding Loom in a Spring Boot service takes three calls (parse, initialise, execute).
- It runs on the JVM your organisation already operates, with its monitoring, security reviews and
  deployment tooling.

---

## Loom and LangGraph

[LangGraph](https://www.langchain.com/langgraph) popularised stateful agent graphs, with checkpoints,
`interrupt()` for human input and a hosted platform. If you have used it, Loom will feel familiar: durable
state, human-in-the-loop, schedules. Loom takes those ideas further for workflows that must run
**unattended, within a budget, for a long time**.

### Where Loom goes further

| | **Loom** | **LangGraph** |
|---|---|---|
| **Writing a workflow** | A small declarative language, read top to bottom. Routing, loops, limits and approvals are statements that non-programmers can review. | A graph of nodes and edges built in Python or JavaScript code, with routing inside conditional-edge functions. |
| **Where the rules live** | In the script, enforced by the runtime, separate from prompts and application code. | Mixed into application code. |
| **Spending limits** | **Built in.** Tokens, calls or money per run, agent, step or loop; checked **before** each call; daily allowances that refill. | Not part of the graph model; you build it. |
| **Rate limits and quotas** | **Built in.** Reads each provider's reset time, pauses the run, and resumes it on its own when the limit lifts. | Retry policies per node; waiting out a daily quota is left to you. |
| **Bounded loops** | Every loop has `max N` and an `on_exhausted` branch, so the workflow decides what happens next. | A recursion limit per run, which ends it with an error. |
| **Scheduling** | `schedule` blocks in the script. Your OS scheduler, Cloud Scheduler or `weave daemon` wakes it; no platform to buy or run. | Cron jobs on its hosted platform, or your own scheduler. |
| **Durability** | Journal per step (memory, file or any SQL database). Durability is a runtime flag: the script doesn't change. | Checkpointers wired into the graph at compile time. |
| **Approvals** | `approve: [Tool]` per agent: only those tool calls wait, and each is journaled. | `interrupt()` placed in node code. |
| **Trusting the agent** | **Built in.** `decision` ledger, a `watch` → `suggest` → `act` ladder earned from your own cases, blind measurement, freeze, and replay of a change over past cases. | Build it yourself. |
| **Checking before running** | `weave check`: every problem, with its line, before any model is called. | Problems surface at compile or run time. |
| **Providers** | Gemini, Sarvam, Ollama and Claude behind one contract; switching is a one-word change. | Through LangChain integrations. |
| **Runtime** | The JVM, packaged as one JAR, deployed like the rest of your Java estate. | Python or JavaScript. |

### Why teams choose Loom

- **Their stack is Java.** Agent workflows deploy, scale and get monitored like every other service.
- **Workflows run unattended**: overnight, on a schedule, against daily quotas. They pause and resume by
  themselves.
- **Cost must be bounded before the call**, not discovered on the invoice.
- **The orchestration is reviewable.** A `.loom` file reads like the process it describes, so product
  owners, reviewers and auditors can follow it.
- **Autonomy has to be earned and provable.** Moving an agent from "a person decides" to "it acts" is a
  recorded, reversible, auditable step.
- **The rules that keep agents safe live in one place**: bounds, budgets, approvals and guards, separate
  from prompts and application code.

---

## Seen in practice

[GetViral](../../examples/getviral/) is a 12-agent content studio running as a hosted website. It uses
all of this:

- a critic `loop until` with a bound;
- a quality loop that routes each fix with one `for each`;
- human hook-pick and publish gates that suspend instead of holding a thread;
- journaled runs that survive server restarts.

The workflow is one `.loom` file.

**Next:** the [Language Guide](LOOM_GUIDE.md) and
[Budgets, Pausing and Scheduling](BUDGETS_AND_SCHEDULING.md).

---

*LangGraph details reflect its public documentation as of September 2026
([durable execution](https://docs.langchain.com/oss/python/langgraph/durable-execution),
[checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers),
[interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)). Corrections are welcome.*
