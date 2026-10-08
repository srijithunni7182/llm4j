# Security: the building blocks

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)

What each part of llm4j does for security, and how it is meant to be used. The principles behind them are in the [overview](../../SECURITY.md#principles).

## The building blocks

### `ai-agent4j`: the core library

| Capability | What it does | How to use it |
|---|---|---|
| **Per-call approval**: `Tool.requiresApproval(args)` and `ApprovalCallback` | A tool decides, from the actual arguments, whether a person must confirm. Without a callback, a call that needs approval is **blocked**, never run. | Return `true` for anything irreversible or costly (a refund over a limit, a delete, a send). Wire the callback to a real person. |
| **Budgets**: `Budget`, `BudgetedLLMClient` | Caps on tokens, calls or money per run, per agent or per call, checked **before** each model call. | Give every agent a budget. A loop that runs away hits the cap and stops. |
| **PII masking**: `MaskingLLMClient`, `PIIDetector` | Replaces emails, phone numbers, SSNs, card numbers and IP addresses with placeholders (`[EMAIL]`) in every message sent to the model, including tool results and retrieved context. | Wrap the client of any agent that handles customer data, especially with a hosted model. |
| **Bias monitoring**: `BiasMonitor` | Flags sweeping statements about groups of people, by rules or by a judging model. | Use it on agents whose output reaches customers or affects decisions about people. |
| **Audit**: `AuditLogger` | Records what agents did (tool calls, approvals, detections) as structured events, with personal data masked. | Point it at durable storage in production. |
| **Rate limits and typed errors** | `RateLimitException` carries the provider's reset time. `AuthenticationException` and `ContentBlockedException` are distinct types. | Handle each one deliberately. Never treat a content block as a retryable error. |
| **Effect contracts**: `Effectful`, `EffectJournal`, `ToolKind` | The contract a tool implements to say whether a call is a read or an effect, and to journal effects so they are not repeated. | Implement them in your own tools that change the world. |
| **MCP and OpenAPI adapters** | Turn an MCP server's tools or an OpenAPI spec's operations into agent tools. | **These widen what an agent can do.** Only connect MCP servers you trust as you trust your own code, and give OpenAPI tools credentials scoped to what the agent needs. |
| **Sub-agents**: `delegate_task` | A manager agent builds a sub-agent on the fly with tools picked from a `ToolRegistry`. | **The registry is the permission boundary.** The manager can hand a sub-agent any tool in it, so register only what any sub-agent may hold. In Loom, prefer workflow delegation ([securing a workflow](securing-workflows.md), step 5). |

### `ai-agent4j-tools`: tools built for a hostile caller

Six ready-made tools, written for the case where the model chooses the arguments and may be wrong, tricked or
hostile. Each one's options set the fence, and the tool checks every call against it. Full detail:
[the tools' safety model](../../src/ai-agent4j-tools/docs/safety.md).

| Tool | Its fence |
|---|---|
| `webhook` | A fixed URL. The model supplies only the text and a one-line title. Internal addresses are refused. |
| `email` | A fixed `to`, or an `allow_to` list. A strict address parser; line breaks refused (no header injection). One refused recipient cancels the message. `max_per_run`, counted across resumes. STARTTLS required. |
| `http` | Only paths below a fixed `base_url`. Methods and path patterns allow-listed. The model sets no headers and no host. Private and link-local addresses refused, including the cloud metadata address, checked against the address actually connected to. No redirects by default. |
| `file` | One directory. Paths checked after resolving symbolic links. Hidden files and the run's own journal refused. Name patterns. Atomic writes. No delete. |
| `shell` | An allow-list of programs, resolved on `PATH`. Shells and interpreters refused unless explicitly allowed. Arguments passed as an array to a process with no shell, so `;`, `\|` and `$( )` mean nothing. Cleared environment, output and time caps, whole-tree kill. Must be under `approve:` or explicitly marked unattended. |
| `sql` | A read-only connection plus a statement check: one `SELECT`, `WITH` or `VALUES`, no writes, no `INTO`, no dangerous functions. Values only as bound parameters. Row and byte caps. |

All six share these properties:
- secrets come only from `env.NAME`;
- everything is checked when the script loads, and nothing connects at load;
- every call has a timeout and a size cap;
- a refusal comes back to the model as text, never as an exception;
- secrets are scrubbed from every result.

### Loom: workflows the runtime governs

Loom is where the principles become a language. The script is the authority, and it reads like the process it
describes, so a reviewer, an auditor or a manager can check it.

| Capability | What it does | How to use it |
|---|---|---|
| **Declared tools per agent**: `tools: [...]` | An agent can call only the tools listed for it. The list is checked when the script loads. | Give each agent the smallest list that does its job. |
| **Secrets from the environment only**: `env.NAME` | A literal password, token or credential header in a script is a **load error**. | Keep secrets in the environment or a secret manager, never in the script or the repository. |
| **`weave check`** | Reports every problem, with its line, before any model is called: unknown tools or agents, missing keys, bad options, approvals for tools the agent doesn't have, unsafe tool settings. | Run it in CI on every script, like a compiler. |
| **Approvals**: `approve: [Tool]` or `approve: all` | Each listed tool call waits for a person's yes. The answer is journaled against the exact arguments, so a resume never asks twice, and a different call is asked again. | Approve every tool that sends, posts, writes outside a sandbox, spends money or runs a program. |
| **Agent guards**: `guard { pii: mask \| block \| warn  bias: warn \| block }` | Masks or blocks personal data in everything the agent sends and receives, and checks answers for bias. Audit lines record kinds and counts, never the data. | `pii: mask` on any agent that sees customer data. `pii: block` where personal data must never reach the model. |
| **Guardrail blocks**: `guardrail (PII) { ... } on_violation { ... }` | Wraps statements and diverts the workflow when personal data is detected. | Use it around steps that take raw user input. |
| **Budgets**: `budget { tokens: ...  calls: ... }` | Run, agent and per-call caps, checked before every call. `per day` makes it refill, and `when_exhausted: suspend` pauses instead of failing. | Put a run budget at the top of every script, and a smaller one on any agent that loops. |
| **Bounded loops and iterations**: `max N … on_exhausted`, `max_iterations` | No loop, retry or reasoning chain is unbounded. The script decides what happens at the limit. | Keep `max_iterations` small for agents that read untrusted content. |
| **Typed results**: `expecting { field: type }` | A delegate's answer must match a schema, or the step fails. | Pass typed, narrow results between agents, not free text, especially across a trust boundary. |
| **Durable runs and the journal** | Every step is recorded. A crash or a pause resumes where it stopped, and effects already performed are never repeated. | Use `--journal` for anything that sends or spends. |
| **Rewind with a side-effects policy**: `side effects: ask first` | Going back past effects that already happened needs a person's decision by default. A tool whose effects are unknown is held, not repeated. `weave fork --effects simulate` re-runs with nothing performed. | Keep the default `ask first`. Use simulated forks to try changes on real history safely. |
| **Earned autonomy**: `decision` and `decide` | An agent proposes and a person decides. A ledger of both lets the agent move from `watch` to `suggest` to `act`, and back, on a conservative statistical bound. Agreement is measured blind; the default ceiling is `suggest`; a broken ledger fails closed to `watch`; `weave autonomy freeze` stops `act` at once. | Use it instead of a one-off "go live" decision. Cap risky actions with `never go above suggest`. |
| **Answers from your phone**: `--ask-via telegram` | Questions and approvals reach you in a chat. Only allowlisted chats and users can answer. Approvals need the question's code in the reply. `watch` questions never show the proposal. The bot polls outwards, so **nothing listens on a port**. | Use a private chat with your bot, and turn on two-step verification on that Telegram account. |
| **Audit logs and operator records** | Approvals, detections, budget refusals, level changes, freezes, answers, and every operator action (`rewind`, `fork`, `promote`, ...) with its `--reason`. | Keep the store's audit files. They are your record of who allowed what. |
| **Reserved paths** | The `file` tool can't reach the run's journal, the trigger store (with the questions waiting for answers) or the autonomy ledger. | Nothing to do: an agent cannot rewrite its own history or promote itself. |

### Engram, the addons and eval4j

- **Engram** keeps long-term memory as extracted facts rather than whole transcripts. Combine it with
  `guard { pii: mask }` so personal data is masked before anything is stored.
- **Addons** run embeddings locally (ONNX, DJL). Documents you index never leave the machine, and there is no
  per-token bill.
- **eval4j** turns safety behaviour into a build gate. It has AssertJ assertions on which tools an agent called
  (or didn't), and judges for bias, toxicity and groundedness, run in `mvn test`. Write a test for every refusal you
  depend on.

---

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)
