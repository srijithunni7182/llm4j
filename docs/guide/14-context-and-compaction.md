# 14. Context and compaction: keep a long run inside the model's window

**Goal:** an agent that loops over tools for dozens of steps never sends the model a prompt it cannot hold, never pays to resend what it no longer needs, and you can see it happen.

An agent's prompt grows with every thought, tool call and result, and the whole of it is sent again on the next call. Sooner or later it no longer fits the model's **context window**, or it fits and every call costs more than the last. This chapter is about deciding what an agent should do about that, writing it down, checking it, and watching it work.

## Do you need this?

Most workflows do not. A **short run** (an agent that answers in a few steps, a classifier, a one-shot writer) never comes near its window: leave it alone, there is nothing to configure. You need it when:

- an agent **loops on tools** (search, fetch, read a file, query) for many iterations, and each result is long;
- a run is **long-lived** (a research agent, a coding agent, a support thread) and its history keeps growing;
- you have seen the provider's **"prompt too long"** error, or the cost per call climbing during one run, or answers cut off (`truncated`);
- an agent shares a **transcript** with others that has no bound.

Ask the user these, and write the answers in the project's README:

1. Which agents loop on tools? Those are the only ones that need a `context` block.
2. Which model does each use, and what is its window? (A model's window is a fact about the model: it is written once, outside the script; see below.)
3. What may be lost? Old tool results are usually safe to shorten; the user's request and the latest steps are not. Is there anything that must never be dropped?
4. May a cheaper model write notes about the old steps (more faithful, but a model call that costs money), or is "forget the oldest" enough?

## Declare it: the `context` block

Put the block in the agent. Only `compact` is required; `context { compact: drop_oldest }` is a complete, useful declaration, and `compact: none` switches it off.

<!-- weave check -->
```loom
agent Researcher {
    model: "claude-sonnet-5-5"
    tools: [web_search]
    max_iterations: 40
    context {
        compact: chain(trim_tool_results, summarize, drop_oldest)   // tried in order, only as far as needed
        summarizer: "claude-haiku-5-5"                              // the cheaper model that writes the notes (for summarize)
        trigger: 80%                                                // start compacting above this share of the usable window
        target: 50%                                                 // compact down to this share
        keep_recent: 6                                              // the latest messages are never touched
        max_compactions: 20                                         // a safety stop for one run
    }
}

workflow Main(topic) {
    delegate "Research {topic} and list what you found, with sources" to Researcher -> findings
    note "{findings}"
}
```

The strategies, from cheapest to bluntest:

| Strategy | What it does | Costs a model call? |
|---|---|---|
| `trim_tool_results` (`max_chars`) | shortens long tool results, oldest first, keeping the start and the end of each and saying how much was cut | no |
| `summarize` (`max_summary_tokens`, needs `summarizer`) | replaces the oldest part with notes written by a model; falls back to `drop_oldest` if that goes wrong | **yes**, and the run's cost shows it |
| `drop_oldest` | removes whole exchanges from the old end and leaves one marker | no |
| `chain(a, b, c)` | tries them in order and stops as soon as the target is reached | whichever is in it |
| `none` | nothing | no |

A good first chain is `chain(trim_tool_results, drop_oldest)`: free, and it handles the usual cause of growth (big tool results). Add `summarize` when the old steps carry decisions the agent still needs, and say in the README why.

Defaults when a key is left out: `trigger: 80%`, `target: 50%`, `keep_recent: 4`, `max_compactions: 8`. `target` must be below `trigger`. A run that adds a lot between model calls may need a higher `max_compactions`: when it is used up the agent stops compacting.

Loom also hands each agent a briefing that holds every earlier task and answer of the run (the *transcript*), and that grows without limit. An agent can bound it too:

<!-- weave check -->
```loom
agent Writer {
    model: "claude-sonnet-5-5"
    memory { max_tokens: 4000  compact: drop_oldest }     // the shared transcript never exceeds about 4,000 tokens
}

workflow Main(topic) {
    delegate "Write a short note on {topic}" to Writer -> answer
    note "{answer}"
}
```

## Say what the window is, once, outside the script

The size of a model's window is not written in the script. It is a **windows file**: one model per line, `model = window / tokens reserved for the answer`.

```text
claude-sonnet-5-5 = 1000000 / 128000
my-finetune       = 32768 / 4096
```

Name it `<script>.windows` beside the script, or pass `--context-windows <file>` (repeatable), or keep one for the machine in `~/.llm4j/context-windows`; the current Claude models are already in the catalogue that ships with the jar. A model that is in none of them has no known window: the agent cannot compact by fill (it still recovers from a "prompt too long" error), and `weave check` warns once. On a Fabric server the same table can be kept for every process on its Configuration page (the page's *Context windows*), so a process nobody told the window still gets a gauge.

## Your own strategy

When the built-ins are not what the agent needs (keep every decision, never drop a figure), write a `ContextCompactor` in Java and map it to a name in a `<script>.loct` file, the way a tool is mapped in a `.loot` file:

```text
keep_decisions = com.acme.KeepDecisionsCompactor
```

Then use it by that name in a chain: `compact: chain(trim_tool_results, keep_decisions, drop_oldest)`. It is held to the same rules as the built-ins (it must not change its input, must keep a tool call with its results and must not return more than it was given); an agent discards a result that breaks them. The exact calls are in [chapter 12](12-java-quick-reference.md).

## Check it

```
weave check main.loom      # an unknown strategy, a bad number, target at or above trigger, a window in the script, a summarizer that is not a model; warns when no window is known
weave audit main.loom      # every agent's strategy, its model's window and the file it came from
```

Then run it for free: a scripted model that calls a tool 30 times shows a compaction in the trace (`weave run --trace` prints `context` and `compaction` events: numbers and names only, never a prompt or a result). `weave eval ... --mock` and the trajectory tests of [chapter 8](08-trajectory-tests.md) can assert that the run finished and how many compactions it needed.

## Watch it: what the page shows, and the rules

With `weave run main.loom --observe` (one process) or reporting to a Fabric server (`--observe-fabric`; the **llm4j-loom-deployer** skill does this with you) the run page has a **Context** view: a gauge per agent (its fraction of the usable window, with a mark where it warns and where it becomes high), and a timeline of the prompt size with a marker for each compaction, with its strategy and how much it freed. An agent whose window nobody gave shows a hatched bar and says so; the page never invents a percentage.

Three signals matter here, and each has a threshold you can set per agent in the `<script>.inspect` file (or centrally, on Fabric's Configuration page):

| Signal | It means | Default |
|---|---|---|
| `context_nearly_full` | the prompt is close to the window: compaction is not keeping up, or `trigger` is too high | warn at 85 %, high at 95 % |
| `compaction_thrash` | compaction runs on nearly every call and frees little: the agent adds more than the strategy can take out | 4 compactions in 10 calls |
| `truncated_answers` | the model's reply was cut off at its token limit | 1 time |

```text
Researcher { context_nearly_full: warn at 80%, high at 92% }
* { compaction_thrash: 3 in 8 calls }
```

If **thrash** fires, shorten what the tools return (`max_chars`), raise `keep_recent` less, or give the agent a smaller job: compaction cannot fix an agent that adds more than the window holds.

## Gate

- Every agent that loops on tools has a `context` block with a chain the user agreed to; agents that do not loop have none.
- Each model's window is known (a windows file, the catalogue, or the server's catalogue); `weave check` has no window warning left that nobody explained.
- `weave audit` shows the strategy and the window you expect for each agent.
- A free run that fills the window (a scripted model, many tool calls) finishes, and its trace shows a compaction.
- If the run will be watched, the thresholds for `context_nearly_full`, `compaction_thrash` and `truncated_answers` are written down, even if they are the defaults.
