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

### 10. Ships like any Java application

- `weave package --fat` builds one runnable JAR.
- Embedding Loom in a Spring Boot service takes three calls (parse, initialise, execute).
- It runs on the JVM your organisation already operates, with its monitoring, security reviews and
  deployment tooling.

---

## Loom and LangGraph

[LangGraph](https://www.langchain.com/langgraph) is the most widely used framework for stateful agent
workflows, and a good one. It has durable execution through checkpointers (in memory, SQLite, Postgres),
human-in-the-loop via `interrupt()`, time-travel debugging, streaming, and, on its deployment platform,
background runs and cron jobs. Both are serious tools. They make different bets.

### Where they differ

| | **Loom** | **LangGraph** |
|---|---|---|
| **How you write a workflow** | A small declarative language (`.loom`), read top to bottom. Routing, loops, limits and approvals are statements. | Python or JavaScript code: a `StateGraph` of nodes and edges over a typed state object. Routing is code in conditional edges. |
| **Where the rules live** | In the script, enforced by the runtime. A model can't loop past `max N` or skip an approval. | In your graph code. Just as enforceable, but mixed in with application code. |
| **Durability** | Journal per step (memory, file, SQL). Replay by re-running; the script is unchanged. | Checkpoint per super-step (memory, SQLite, Postgres), keyed by `thread_id`. |
| **Waiting for people** | `human_prompt`, and `approve:` per tool call. The run suspends and holds no thread. | `interrupt()`, then resume with `Command(resume=…)` on the same thread. |
| **Rate limits and quotas** | Built in: reads each provider's reset time, pauses the run and schedules its resume. | Retry policies per node. Pausing until a provider's quota resets is left to you. |
| **Spending limits** | Built in: tokens, calls or money per run, agent, step or loop; checked **before** each call; daily windows that refill. | Not part of the graph model. Call counts can be capped (for example with LangChain's agent middleware); token or money budgets are left to you. |
| **Scheduling** | `schedule` blocks in the script. Your OS scheduler, Cloud Scheduler or `weave daemon` wakes it; no platform needed. | Cron jobs on the LangGraph deployment platform, or your own scheduler calling the graph. |
| **Bounded loops** | Every loop has `max N` and an `on_exhausted` branch. | A recursion limit per run, which raises an error when hit. |
| **Checking before running** | `weave check`: every problem, with its line, before any call. | Python's usual tooling; graph problems show at compile or run time. |
| **Language and runtime** | JVM (Java 17+). The CTK defines the behaviour for other runtimes. | Python and JavaScript. Java ports exist in the community. |
| **Observability** | `--trace` live, spend reports, audit events. | LangSmith (hosted), streaming modes, and the LangGraph Studio visual debugger. |

### Choose Loom when

- your stack is **Java**, and you want agent workflows that deploy like the rest of it;
- workflows run **unattended for a long time** (overnight, on a schedule, against daily quotas) and must
  **pause and resume by themselves**;
- **cost must be bounded** before the call, not discovered afterwards;
- you want the orchestration **reviewable by people who don't write the code**: a `.loom` file reads like
  the process it describes;
- you want the rules that keep agents safe (bounds, budgets, approvals) **separate from prompts and
  application code**.

### Choose LangGraph when

- your team works in **Python or JavaScript** and wants the LangChain ecosystem of integrations;
- you want **arbitrary graph shapes and state reducers** expressed directly in code;
- you want **time-travel debugging**, **LangSmith** and the **visual Studio**;
- you plan to use the **managed LangGraph platform** for deployment.

### What Loom doesn't have (yet)

- No visual graph editor. Loom has the VS Code extension, with diagnostics and an outline instead.
- No time travel. Loom has replay from the journal, but not forking a run from an earlier step with
  edited state.
- A smaller ecosystem. Tools come from ai-agent4j, OpenAPI specs, MCP servers or your own Java classes.
- The Python runtime (loom4py) is still in development.

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

*The LangGraph comparison reflects LangGraph's public documentation as of September 2026
([durable execution](https://docs.langchain.com/oss/python/langgraph/durable-execution),
[checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers),
[interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)). Both projects move quickly;
corrections are welcome.*
