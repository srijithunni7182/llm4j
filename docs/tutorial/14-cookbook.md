# 14. Cookbook and cheat sheet

Recipes for common jobs, the mistakes people make and what Loom says about them, and one page of syntax to keep open while you write.

## Recipes

Each recipe names the starter that implements it. Open the starter, run `weave test` on it, and change it.

| I want to... | Recipe | Starter |
|---|---|---|
| Answer a question | One agent, one `delegate`, `return` | [01](../../src/loom/starters/01-hello/) |
| Improve a draft in stages | A chain: each step's result feeds the next | [02](../../src/loom/starters/02-chain/) |
| Get quality by review | `loop until (review.verdict == "OK") max N` with an `expecting` schema | [03](../../src/loom/starters/03-review-loop/) |
| Send each request to the right specialist | A classifier with an `enum` schema, then `alt` in the script | [04](../../src/loom/starters/04-router/) |
| Ask several sources at once and survive one failing | `parallel`, one flag per branch, `retry` + `on_failure` | [05](../../src/loom/starters/05-parallel-research/) |
| Turn a plan into parallel work | `parallel for each` with `{item.owner}` routing | [06](../../src/loom/starters/06-plan-and-fan-out/) |
| Never send without a person's yes | `human_prompt` + `alt`, and `approve:` on the sender | [07](../../src/loom/starters/07-approval/) |
| Turn text into checked fields | `output_schema`, `halt` on unreadable, ask a person for a missing value | [08](../../src/loom/starters/08-extract-and-validate/) |
| Answer from my documents | `knowledge` + "say when the documents do not answer" | [09](../../src/loom/starters/09-handbook-qa/) |
| Remember each user | `memory { session: "{user_id}" }` + `guard { pii: mask }` | [10](../../src/loom/starters/10-memory-assistant/) |
| Run every weekday morning | `schedule` + `weave schedule sync` + `weave daemon` | [11](../../src/loom/starters/11-daily-digest/) |
| Wait days for a person, then follow up | `human_prompt`, `sleep`, `--journal` | [12](../../src/loom/starters/12-wait-for-a-person/) |
| Go back when a late check fails | `checkpoint` + `rewind ... carrying` | [13](../../src/loom/starters/13-rewind-and-retry/) |
| Stop an agent that loops or runs long | `supervise` rules | [14](../../src/loom/starters/14-supervised-agent/) |
| Let an agent earn the right to act | `decision` + `trust` + `decide` | [15](../../src/loom/starters/15-earned-autonomy/) |

### Recipe: a cheaper model when money runs low

```loom
substitution: braces

budget { tokens: 100000 }

agent Writer      { model: "gemini-2.5-pro"    system: "You write careful, detailed drafts." }
agent CheapWriter { model: "gemini-2.5-flash"  system: "You write short drafts." }

workflow Main(topic) {
    alt (_budget.remaining < 30000) {
        delegate "Draft {topic}" to CheapWriter -> draft
    } else {
        delegate "Draft {topic}" to Writer -> draft
    }
    return draft
}
```

### Recipe: ask a person only when the model is unsure

```loom
substitution: braces

intentclassifier Intent {
    model: "gemini-2.5-flash"
    choose kind: refund | question | other
    samples: 3
    confidence at least 0.8 else escalate
}

agent Refunds   { model: "gemini-2.5-flash"  system: "You handle refund requests." }
agent Questions { model: "gemini-2.5-flash"  system: "You answer questions." }

workflow Main(message) {
    intentclassify Intent on message -> intent
    alt (intent.kind == "unsure") {
        human_prompt "The classifier was not sure about this message. Which team?\n{message}" -> team
        note "A person routed it to {team}."
    } else {
        alt (intent.kind == "refund") {
            delegate "{message}" to Refunds -> reply
        } else {
            delegate "{message}" to Questions -> reply
        }
        note "Answered: {reply}"
    }
}
```

### Recipe: split a big script into files

```loom-parse
// main.loom
import "agents.loom"
import "flows/review.loom"

workflow Main(topic) {
    delegate "Write about {topic}" to Writer -> draft
    call ReviewDraft(draft = draft) -> verdict
    return verdict
}
```

An imported file is checked together with the script that imports it. Keep agents in one file and reusable workflows in another.

### Recipe: a safe way to try something new

Run `weave fork` on a real run to copy it, and try a different script on the copy with `--effects simulate`. Nothing is performed; you see what would have happened ([Chapter 11](11-advanced-orchestration.md#from-outside-timeline-rewind-reset-fork)).

## Mistakes and what Loom says

These are real messages from `weave check`. Each one is something you will meet in your first week.

**A name you mistyped.** Loom suggests the nearest one:

```loom-error
agent Helper { model: "gemini-2.5-flash"  system: "You help." }

workflow Main(question) {
    delegate "{question}" to Helpr -> answer
    return answer
}
```

```text
line 4: delegate Helpr: there is no agent named Helpr; did you mean Helper? Agents defined: Helper.
```

**`&&` or `||` in a condition.** There is no such thing, on purpose. Nest `alt` blocks:

```loom-error
agent Helper { model: "gemini-2.5-flash"  system: "You help." }

workflow Main(question) {
    delegate "{question}" to Helper -> answer
    alt (answer == "yes" && question == "ok") {
        note "both"
    }
}
```

```text
Unexpected character '&' at line 5. A condition is one comparison (== != > >= < <=): there is no && or ||; nest alt blocks instead
```

**An unknown model.** The message lists every form `model:` understands:

```loom-error
agent Helper { model: "totally-unknown-model"  system: "You help." }

workflow Main(question) {
    delegate "{question}" to Helper -> answer
    return answer
}
```

```text
line 1: agent Helper: unknown model "totally-unknown-model": use gemini-…, claude-… (or anthropic/<model>), ollama/<model>, ...
```

### Things `weave check` allows but `weave review` flags

| Mistake | Why it hurts | The fix |
|---|---|---|
| `loop until (...)` with no `max` | A model that is never satisfied runs, and bills, forever | `max N` and an `on_exhausted` block |
| A variable set and never read | Usually a typo, or a branch that does nothing | Use it or delete it |
| A variable that is also a plain word in a prompt | In the older mode the word is replaced by the value | `substitution: braces` |
| A tool step with no `retry`/`timeout`/`on_failure` | One slow call stalls or ends the run | Add all three |
| An agent that can send, with no `approve:` | Nobody decides before it acts | `approve: [Tool]`, or a `human_prompt` first |
| One shared variable written by two `parallel` branches | A race: you get one of the answers | One variable per branch |
| Both answers to a `human_prompt` lead to the same steps | The question changes nothing | Give each answer its own branch |
| A key written in the script | A load error, on purpose | `env.NAME` or `secret.NAME` |
| `rewind` over steps that send something | The replayed attempt might send again | `side effects:` says what you mean; put the check before the effects |

## Syntax cheat sheet

```text
// comment
substitution: braces
import "other.loom"

budget { tokens: 200000  calls: 150  warn_at: 80% }
rate_limits { on_limit: suspend  max_wait: 24h  max_resumes: 50 }
shared_prompt: "rules for every agent"
audit { logger: "file"  path: "audit.log" }

persona Name { role: "..."  tone: "..."  constraints: ["..."] }
provider Name { use: ollama  base_url: "..."  api_key: env.NAME }
routing Name { strategy: fallback  primary: "m1"  fallback: ["m2", "m3"] }
knowledge Name { source: "docs/"  embedding: "gemini/text-embedding-004"  top_k: 4  store: "idx.json" }
tool Name { use: webhook|email|http|file|shell|sql|openapi|class|...  ...options... }
mcp Name { transport: "stdio"  cmd: "..." }
intentclassifier Name { model: "..."  choose k: a | b  ask q: "..."  rate r: lo | hi  confidence at least 0.8 else escalate }

agent Name {
    model: "..." | routing: Policy      system: "..." | prompt: "id@v1"
    persona: P   temperature: 0.2   max_iterations: 8
    tools: [A, B]   approve: [A] | all   knowledge: [K]   skills: ["fs://x.md"]
    output_schema: { f: string, n: number, b: boolean, e: enum["A","B"], l: list<string> }
    budget { tokens: N  per_call: N }   memory { ... }   guard { pii: mask|block|warn  bias: warn|block }
    context { compact: chain(trim_tool_results, summarize, drop_oldest) }
    streaming: true   steerable: true   grounding: web { ... }
}

supervise Agent { when looping(3) { steer "..." once }  when steps > 15 { cancel use partial } }
decision Name { proposed by: A  choices: x, y  ...  trust { ... } }
schedule Name { cron: "0 7 * * 1-5"  timezone: "..."  run: Wf(arg="v")  misfire: run_once  overlap: skip }

workflow Name(p1, p2) {
    delegate "task {p1}" to Agent -> result  [expecting {...}] [retry N] [backoff 2s] [timeout 90s] [budget N tokens] [on_failure {...}] [on_stopped {...}]
    broadcast "task" to [A, B] -> result
    handoff "task" to Agent
    parallel { ... }
    for each x in list.items { ... }          parallel for each x in list.items { ... }
    alt (a == "b") { ... } else { ... }
    loop until (a == "b") max 5 { ... } on_exhausted { ... }
    call Other(arg = value) -> result
    run Task(arg = value) -> result [model "..."] [retry N] [timeout 30s]
    intentclassify Name on text -> result
    decide Decision -> verdict
    human_prompt "question {x}" -> answer
    sleep 5m                                 set x = "text {y}" | 3 | true | { k: v }
    checkpoint name starting with v = "..."  rewind to name when (...) at most 2 times carrying v = "..." side effects: keep
    note "message"     observe "label" {x}     halt "why"     return value
    guardrail (PII) { ... } on_violation { ... }
}
```

## Command cheat sheet

```text
weave init <template> [dir] --flat      start a project (pipeline, approval, classifier, empty)
weave check  main.loom [--no-env]        does it load? (free)
weave next                                what should I do now?
weave graph  main.loom --format mermaid   a picture;  weave explain main.loom   in plain English
weave test   main.loom --coverage         scripted models, free
weave audit  main.loom                    security (free)      weave review main.loom   everything, ranked
weave eval   main.loom --init|--check|--mock|--max-tokens N    golden datasets
weave run    main.loom -i k=v --max-tokens N --journal DIR     a real, capped, durable run
weave resume DIR                          continue a paused run
weave timeline DIR | rewind | reset | fork                       look at and steer a run
weave schedule sync | daemon | tick | triggers install           run on a clock
weave questions STORE | answer STORE CODE yes                    people in the loop
weave package main.loom --fat             a runnable jar        weave deploy main.loom --target compose
weave run main.loom --observe             a live page           weave fabric up|status|down
weave capture | regress | gate            production -> tests -> one CI verdict
weave metamorphic | judge validate | security generate | probe eviction    stronger evaluation
weave man <command>                       the manual page for any command
weave guide                               the built-in language guide
```

## Glossary

| Term | Meaning |
|---|---|
| **Agent** | A model plus its instructions, tools and limits |
| **Workflow** | A named list of steps; can take inputs and return a value |
| **Delegate** | Give a task to an agent and store its reply |
| **Variable** | A named value; `{name}` puts it in text |
| **Tool** | Something an agent may call |
| **Task** | A step written in Java that must be exact |
| **Guard** | A protection on an agent (personal data, bias) |
| **Budget** | A cap on what model calls may spend |
| **Journal** | The record of every step that lets a run resume without repeating itself |
| **Checkpoint / rewind** | A named point to go back to, and the act of going back |
| **Effect** | Something a step does to the outside world; effects are recorded and never repeated |
| **Degraded** | A run that finished but used a fallback |
| **Halt** | Stopping on purpose, with a reason; not a failure |
| **Scripted model** | A fake model in a test that replies exactly what you told it to |
| **Golden dataset** | Examples of good behaviour a real evaluation is scored against |
| **Lethal trifecta** | Untrusted input + private data + the power to send: the dangerous combination |

## Where to go from here

- Build something: pick the starter nearest your idea and change it.
- Know every option: [LOOM_GUIDE.md](../../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) and `weave man <command>`.
- Work to a process, with evaluation: [the workflow guide](../guide/README.md).
- Put it in front of people: [Going live](../guide/09-go-live.md).
- Understand the safety model: [Secure by construction](../security/secure-by-construction.md).
- Get your coding agent to help: `weave guide --install-skill` teaches Claude Code, Copilot, Cursor or any `AGENTS.md` agent to build with Loom, and to look commands up instead of guessing.

Back to the [tutorial index](README.md).
