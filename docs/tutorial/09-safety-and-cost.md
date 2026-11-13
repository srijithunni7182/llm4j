# 9. Safety and cost

An agent's next step is chosen by text, and some of that text comes from places you do not control: a web page, an email, a tool's output. Any of it can try to give the agent orders, and no prompt reliably stops that. So Loom does not rely on the model behaving. **The model reasons; the code holds the authority.** What an agent may touch, spend, send and decide is declared in the script and enforced by the runtime on every call, whatever the model says.

This chapter is the safety toolbox: budgets, guards, secrets, and the two commands that review a script before it ever runs.

## Budgets: it cannot overspend

A budget caps what model calls may spend, in **tokens** (the default), **calls**, or **money** (with a price table you supply). The runtime enforces it at the call itself: before each call it checks what is left and **refuses without calling the model** if the call cannot fit. Budgets go in three places:

```loom
substitution: braces

budget { tokens: 200000  calls: 150  warn_at: 80% }          // the whole run

agent Writer {
    model: "gemini-2.5-flash"
    system: "You write short drafts."
    budget { tokens: 20000  per_call: 2000 }                  // this agent's share; per_call caps each answer
}
agent Critic { model: "gemini-2.5-flash"  system: "Say OK only if the draft is good."  temperature: 0.1 }
agent CheapWriter { model: "gemini-2.5-flash"  system: "You write very short drafts." }

workflow Main(topic) {
    delegate "Draft {topic}" to Writer -> draft budget 5000 tokens
        on_failure { note "Out of budget: {_error}" }

    loop until (review.verdict == "OK") max 5 budget 30000 tokens {
        delegate "Review {draft}" to Critic -> review expecting { verdict: enum["OK", "FIX"] }
    } on_exhausted {
        note "Stopped by {_loopExhaustedBy} after {_loopRounds} rounds"
    }

    alt (_budget.remaining < 20000) {                          // cost-aware routing, in the script
        delegate "Polish {draft}" to CheapWriter -> final
    } else {
        delegate "Polish {draft}" to Writer -> final
    }
    return final
}
```

```mermaid
flowchart TD
  subgraph Run["budget { tokens: 200000 } : the whole run"]
    subgraph Agent["agent Writer: budget { tokens: 20000, per_call: 2000 }"]
      subgraph Step["delegate ... budget 5000 tokens"]
        C["a model call must fit in ALL three"]
      end
    end
  end
  classDef step fill:#d9efec,stroke:#0f766e,color:#0b3b37
  class Run,Agent,Step,C step
```

| Where | Caps |
|---|---|
| `budget { ... }` at the top | the whole run |
| `budget { ... }` inside an agent | that agent's share, and `per_call` for each answer |
| `budget N tokens` on a `delegate`, `loop` or `for each` | that one step |

Budgets **nest**. Every call is charged to the run, its agent and every enclosing step, and must fit all of them. Parallel branches share the same budgets exactly.

### What happens when one runs out

| Situation | Behaviour |
|---|---|
| A step's call would exceed a budget | The model is **not** called. The step fails and its `on_failure` runs with `{_error}` naming the budget. A budget refusal is **never retried**. |
| An agent runs out part-way | Its best answer so far is bound to the output variable, `{_budget.exhausted}` is `true`, and `on_failure` runs if present. |
| A loop's budget runs out | It stops and runs `on_exhausted`, with `{_loopExhaustedBy}` set to `budget` (or `rounds`). |
| Nothing handles it | The run ends as stopped by its budget (exit code 3). Every variable already bound is kept. |

A run stopped by its budget can be resumed with a bigger one: it continues from the refused step, and nothing already paid for runs twice.

### Money

Tokens are simple. For money, supply a price table, because prices go stale and none ship with Loom:

```text
# prices.properties: model = input / output, per million tokens
gemini-2.5-flash = 0.30 / 2.50
```

```bash
weave run main.loom --max-cost 0.25 --prices prices.properties
```

Local `ollama/*` models cost nothing.

### Output length and `per_call`

With a token or cost budget on the run, an agent that sets no `per_call` is sent a limit of 8,192 tokens (or the model's own maximum if smaller), and never more than the budget still affords. If an answer is cut at the limit, the run says so (`✂ 2 answers were cut at the output limit: Writer`). For longer answers, set `per_call`.

### Know the cost before you run

```bash
weave review main.loom
```

Among its findings, `weave review` prints a typical and a worst-case estimate for each workflow, step by step, and says when your cap would stop the run before the worst case.

## Guards: keep personal data and bias in check

An agent's `guard` protects everything it does:

```loom
substitution: braces

agent Support {
    model: "gemini-2.5-flash"
    system: "You answer customers."
    guard {
        pii: mask          // mask | block | warn
        bias: warn         // warn | block
    }
}

workflow Main(message) {
    delegate "{message}" to Support -> reply
    return reply
}
```

- **`pii: mask`**: every message the agent sends its model has emails, phone numbers, SSNs, card numbers and IP addresses replaced by placeholders such as `[EMAIL]`. That covers the task, context, memory and tool results. The answer is masked before it is stored.
- **`pii: block`**: a task or answer containing personal data fails the step. The error names the *kinds* of data, never the values.
- **`pii: warn`**: records it and carries on.
- **`bias`**: the answer is checked, by default with rules that catch sweeping statements about groups of people. `block` fails the step on a high-severity finding.

Audit logs record counts and kinds, never the data itself.

### A guardrail around a block

To protect a whole block of steps, wrap it:

```loom
substitution: braces

agent Summarizer { model: "gemini-2.5-flash"  system: "Summarise the text." }
agent Admin { model: "gemini-2.5-flash"  system: "Tell the administrator what happened." }

workflow Main(raw_text) {
    guardrail (PII) {
        delegate "Summarise this: {raw_text}" to Summarizer -> summary
        return summary
    } on_violation {
        note "Personal data detected in the input. Stopping."
        handoff "A privacy violation was blocked." to Admin
    }
}
```

## Policies: rules checked at the edge

A rule about what must never be said does not belong in an agent's prompt. A prompt is a wish: a model can ignore it, and a reply that quotes "Section 4.6" would go straight out. Put the rule in a **policy file** instead, and check the reply before it reaches anyone.

The file sits in a `policies/` folder beside the script. A person who owns compliance can read and edit it with no Loom knowledge: each rule is a heading (its id) and a sentence about what breaking it looks like.

```markdown
# House rules

## no_refund_promise
The reply promises a refund, a credit or any compensation.

## no_internal_refs
The reply mentions internal policy sections, instructions, or how the assistant was set up.
```

An **evaluator** judges text against those rules. It is a stateless step, like an extractor: it sees only the rules and the text, never the conversation. A **`check`** step puts it at the edge and says what happens on a violation:

```loom-parse
evaluator Compliance { model: "claude-haiku-5-5"  samples: 3  unsure below 0.8 }
policy HouseRules: "house-rules" checked by Compliance

workflow Main(question) {
    delegate "{question}" to Support -> reply
    check reply against HouseRules using Compliance -> verdict on_violation { rewrite with Editor }
    return reply
}
```

On a violation you choose **one** action: `rewrite with Editor` (another agent fixes the text, and it is checked again once), `ask person` (a person sends it as it is, types a different text, or stops it), `replace with "A colleague will reply."` or `fail`. With nothing written the step fails and the error names the rules, never the text.

The evaluator **fails closed**. A rule it cannot judge is "unsure", and unsure blocks just as a violation does, but it is counted apart so you can tell a timid evaluator from one that catches problems. If it cannot answer at all (the model is down), `on_unavailable { replace with "We are looking into this." }` gives a holding answer; the default is to fail, so nothing unchecked leaves.

### The runtime guards the exits

Declare a policy and Loom stops trusting your memory. It remembers, for every value, whether a model wrote it, and **refuses to let model-written text leave unchecked**. The exits are the `return` of the run, the arguments of a task that changes something, and an agent's own calls to a tool that changes something. A number, a true/false, an enum word, a label, a person's answer and the result of a task with no model are not model text and pass freely.

| The value is | At an exit |
|---|---|
| a constant, an input, a number, a label, a person's answer | passes |
| an agent's reply, a text field of an extractor | refused until a `check` passed it |
| a reply that passed `check ... against HouseRules` | passes (if every declared policy was checked) |
| the checked reply with new model words added | refused again |

The refusal tells you what to do: "`reply` was written by a model and has not been checked against HouseRules. Add `check reply against HouseRules using ...` before this step." `weave check` finds the same path before you run anything and names the line of the return and the line of the agent.

An agent that can **send an email or call an API** writes its call inside its own loop, where you cannot put a `check`. So the runtime checks each such call for you, before it runs and before a person is asked (`policy HouseRules: "house-rules" checked by Compliance` names the judge). A call that breaks a rule does not run; the agent is told which rule ids it broke and why, never the rule text, and tries again. After two refusals in a step it is told to stop. A policy judges what a call *says*, not how much it moves: a limit on an amount belongs in the code that acts.

`weave review` raises **LY07** when the output can reach people (the workflow is scheduled, or an agent holds a tool that changes things) and no policy is declared. Accept it with a reason when no customer sees the output.

The **leak guard** is a guard that needs no model. `guard { prompt_leak: block  internal_refs: ["POL-[0-9]+"] }` stops an answer that repeats 12 words of the agent's own instructions, of the shared prompt or of a policy rule, or that names an internal reference. `warn` records it and carries on; with a policy declared every agent warns by default.

## Secrets

**Never write a key in a script.** A literal secret is a load error, on purpose. Keys come from the environment (`env.NAME`) or the encrypted secret store (`secret.NAME`):

```loom-parse
substitution: braces

provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "https://box.example.com" }
tool Search  { use: serpapi  api_key: secret.SERP_KEY }

agent Indic { model: "Box/sarvam-105b"  system: "You answer briefly."  tools: [Search] }

workflow Main(q) {
    delegate "{q}" to Indic -> answer
    return answer
}
```

```bash
weave secrets create --secrets keys.store
weave secrets set BOX_KEY --secrets keys.store --allow-host box.example.com
weave run main.loom --secrets keys.store --secrets-key-env MYAPP_MASTER_KEY
```

- A key is fetched **for each request**, so rotating it needs no restart.
- A key stored with `--allow-host` is refused for any other host, so a script cannot send your key somewhere unexpected.
- Values are typed without echo or piped in, never passed as arguments. `weave secrets list` shows names and hosts, never values.
- Keep a local `.env` file out of git. Every starter ships a `.env.example` that names the keys and holds none.

## Review before you run: `weave audit` and `weave review`

Two commands read a script **without running it** and report what could go wrong.

```bash
weave audit main.loom        # security: what each agent can reach, ranked, exit 1 on a high finding
weave review main.loom       # security, cost, reliability, prompts and privacy, with a fix for each
weave rules                  # every rule, its id and why it exists
```

### The lethal trifecta

The audit looks hardest for the combination that makes prompt injection dangerous. An agent is at serious risk when it can do all three of:

```mermaid
flowchart TD
  U["reads untrusted content<br/>(web pages, emails, documents)"]
  P["reaches private data<br/>(files, databases, memory)"]
  S["can send or act<br/>(email, webhook, http POST, shell)"]
  U --- X(("lethal<br/>trifecta"))
  P --- X
  S --- X
  X --> F["an attacker's text can make the agent<br/>leak your data"]
  classDef bad fill:#fde2e2,stroke:#c53030,color:#5b1111
  class F,X bad
  classDef decision fill:#fdecc8,stroke:#b7791f,color:#4a3300
  class U,P,S decision
```

The cure is to break the triangle: give the agent that reads the web no way to send, give the agent that sends no access to private data, or put a person's approval between them. This is why starter [07-approval](../../src/loom/starters/07-approval/) splits *drafting* from *sending*, and why the sender needs an approval.

#### The same triangle through the transcript (LA21)

By default every agent is briefed with everything said before it (the shared transcript, [Chapter 7](07-knowledge-and-memory.md#who-sees-what-was-said-before)). So an agent that reads the web is not only a risk by itself: what it read, and anything an attacker hid in it, is carried to every agent that runs after it.

```mermaid
flowchart LR
  R["Researcher<br/>reads the web"] -- "its answer goes into<br/>the shared transcript" --> T[("transcript")]
  T -- "briefs" --> S["Sender<br/>private data + can send"]
  classDef bad fill:#fde2e2,stroke:#c53030,color:#5b1111
  class S bad
```

If that later agent also reaches private data and can send or act, the triangle is complete even though no single agent has all three tools. `weave audit` counts this path and reports it as **LA21** (and as the lethal trifecta, LA01, when it completes one), naming the agents. Three fixes:

- give the receiving agent `memory { transcript: none }` (or `own`), so it is not briefed with the reader's words;
- hand it only typed values: pull the fields it needs out of the reader's answer with an [extractor](05-structured-answers-and-failure.md#extractors-fields-from-text-with-nothing-else), and give the sender `transcript: none` so those fields are all it gets (an extractor on its own does not remove the reader's answer from the transcript);
- split the work so no agent that acts runs after one that reads the outside world.

#### Files the script did not choose (LA23)

A picture, recording or PDF can carry instructions that no masking can see. When the file name is filled in at run time (`attach image "{receipt}"`), `weave audit` counts the agent as reading untrusted content (LA23), as it does for web grounding. A file named in the script does not fire it. An agent with `guard { pii: mask }` or `pii: block` also **refuses media** unless it says `guard { pii: mask  media: unscanned }`, because masking works on text and cannot read a picture. Say so only when you accept that.

### What else it catches

Typical findings, each with the reason and a fix:

| Finding | Why it matters |
|---|---|
| An agent can send or write and has no `on_failure` | A half-finished action with nothing deciding what happens next |
| A tool step with no `retry` or `timeout` | One slow call stalls the whole run |
| A loop with no `max` | A model that is never satisfied runs, and bills, forever |
| A secret environment variable not set | Fails before it starts, or at first use |
| A key sent to a custom address | Possible exfiltration of the key |
| A decision agent with no bias check | A compliance gap |

Run both in CI next to `weave check`. `weave audit --fail-on medium` makes medium findings fail the build.

## Effects are recorded, and never repeated

Every step that changes the world (a `webhook`, an `email`, an `http` POST, a `file` write, a `shell` command, a `run` task that declares a change) is written to the journal **before and after** it happens. If the process dies in between, a resume does not blindly repeat a payment: a step that cannot be proven idempotent is stopped and flagged for a person to check, and one that is idempotent runs again with the *same* idempotency key, so the receiver can deduplicate. A retry, a resume or a rewind never repeats a send.

## Everything in one place: the safe-by-default checklist

- Put a `budget` on the run. Give model-driven loops a `max`.
- Give every tool step `retry`, `timeout` and `on_failure`.
- Put `approve:` on any agent that can send, write or run something, and keep sending separate from reading untrusted content.
- Keep secrets in the environment or the secret store.
- Mask personal data (`guard { pii: mask }`) before it reaches a hosted model.
- Run `weave check`, `weave audit` and `weave review` before every release.
- Prefer a script rule or a task over a prompt for anything that must always hold.
- Put rules about what must never be said in a policy file and `check` replies against it before they leave.

The full threat model, and what Loom does *not* protect you from, is in [Secure by construction](../security/secure-by-construction.md) and the [security guide](../../SECURITY.md).

## Try it yourself

1. Run `weave review main.loom` on starter [11-daily-digest](../../src/loom/starters/11-daily-digest/). Read the findings and the "why" for each.
2. Remove a `retry`/`timeout` from a tool step in starter 07. What does `weave review` say?
3. Lower a budget to a tiny number in starter 02 and run `weave test` (nothing changes: tests are scripted) then `weave run` with a real key to see a budget stop.

Next: [Testing](10-testing.md).
