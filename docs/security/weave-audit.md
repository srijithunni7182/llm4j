# Auditing a script: `weave audit`

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)

A static security review of a Loom script, mapped to the OWASP Top 10 for LLM Applications.

## What it does

`weave audit` reviews a script without running it or contacting anything:
- it works out what each agent can reach, and where untrusted content, private data and a way out meet in one agent;
- it lists effects that nobody approves, risky tool settings, missing budgets, and anything that comes from outside the
  repository;
- every finding names the [OWASP Top 10 for LLM Applications (2025)](https://genai.owasp.org/llm-top-10/) risks it
  bears on, says why it matters, and says what to change.

```bash
weave audit briefing.loom                          # a Markdown report; exit 1 if anything is high
weave audit briefing.loom --format json --out audit.json
weave audit briefing.loom --fail-on medium         # stricter gate for CI (high, medium, low, info or none)
```

An excerpt of a report on a script that gives one agent web search, a data folder and an email tool:

```text
# Security audit: helper.loom

**4 findings**: 1 high, 2 medium, 1 low, 0 info.

| Agent | Reads untrusted content | Reaches private data | Can send or act | Effects without approval | Budget | PII guard | Trifecta |
|---|---|---|---|---|---|---|---|
| Helper (line 3) | web_search | Files | Mail | Mail | none | none | **yes** |

### HIGH | LA01 | One agent holds the lethal trifecta with an open way out
- Where: Helper (line 3)
- OWASP: LLM01:2025 Prompt Injection, LLM02:2025 Sensitive Information Disclosure, LLM06:2025 Excessive Agency
- Risk: Helper reads untrusted content (web_search), reaches private data (Files) and can send or act (Mail). ...
- Fix: Split the work: one agent reads the outside world, another touches your data, a third sends; ...
```

The report ends with a table of all ten OWASP risks: the findings for each, what the script has in place, and what
the runtime always enforces.

| Rule | Finds | Severity | OWASP |
|---|---|---|---|
| LA01 | One agent that reads untrusted content, reaches private data and can send or act | high (medium if every way out is approved) | LLM01, LLM02, LLM06 |
| LA22 | A model's answer chooses which agent runs (`delegate … to {task.owner}`): graded by the most dangerous agent it could reach; high when unbounded and some agent changes things or reaches private data, low when it can only reach agents that read | high / medium / low | LLM06 |
| LA02 | An effect with no approval: a shell tool running unattended, an outward tool, a local write; a writing `http` tool with no `allow_paths`. For a tool that changes something outside, its advice offers the boundary first: let the agent propose, then `use Tool(...) approve` or `run Task(...) approve` after it; `approve:` on the agent is for an assistant a person works with as it goes | high / medium / low | LLM06 |
| LA03 | No run budget | medium | LLM10 |
| LA04 | An agent that reaches private data with no PII guard | low | LLM02 |
| LA05 | `allow_private` (internal and cloud-metadata addresses), `allow_http` | high / medium | LLM06, LLM02 |
| LA06 | A `shell` tool allowed to run interpreters | high | LLM06, LLM05 |
| LA07 | Email recipients chosen by pattern; mail without TLS | low / medium | LLM06, LLM02 |
| LA08 | MCP servers; an OpenAPI spec, skills or an import fetched from the network | medium | LLM03 |
| LA09 | Long-term memory on an agent that reads untrusted content | medium | LLM04, LLM01 |
| LA10 | A decision that may reach `act` with automatic promotion, or with no ongoing check | medium / low | LLM06, LLM09 |
| LA11 | A rewind that repeats side effects | medium | LLM06 |
| LA12 | A `file` tool that may overwrite | low | LLM06 |
| LA13 | Indexed documents (they become context) | info | LLM04, LLM08 |
| LA14 | A tool the audit can't see into (a Java class, a host-registered tool): assumed to do everything | low | LLM03, LLM06 |
| LA15 | A provider sends its key to a custom `base_url` (medium for `env.`, low for `secret.`, where an `--allow-host` binding refuses other hosts) | medium / low | LLM02, LLM03 |
| LA16 | An agent that writes programs (its persona or prompt says so) is given text that an agent reading the outside world produced, through a hand-off that is not typed: a person will run output written from untrusted text | info | LLM01, LLM05 |
| LA18 | An agent is `steerable: true`: a person's text reaches it while it works (an input channel). Medium when that agent also reaches private data and can act | info / medium | LLM01 |
| LA19 | An `intentclassifier` on a hosted backend (Jev, or a hosted chat model): the state of its `intentclassify` steps leaves this machine. Lists where it goes and the fields sent; marks a chat model that gates on its own confidence as uncalibrated; notes whether `pii:` masks personal data first | info | LLM02 |
| LA20 | An agent has `grounding: web`: it searches the web inside its model calls. Any page it finds can carry instructions, and the search service sees the queries the model writes. Counts as reading untrusted content for the trifecta whatever its `allow:` list says (an allow list narrows where it reads, it does not make the pages trusted). Low without an allow list, info with one | low / info | LLM01, LLM02 |
| LA21 | An agent receives the whole run transcript (`transcript: all`, the default) at a point where an agent that reads untrusted content has already run in the same workflow: in statement order, through `call` and `handoff`, and in a later round of a loop. Text the reader put in the transcript reaches it whatever its prompt says, so it counts as reading untrusted content. Where it also reaches private data and can act, LA01 fires for it too, naming the path. The severity is LA01's when the trifecta completes, info otherwise. `transcript: none` or `own` on the receiver breaks the path; an extractor between the two does not by itself, because the reader's answer is stored in the transcript either way | high / medium / info | LLM01 |
| LA23 | An agent is sent a picture, a recording or a document whose file name is filled in at run time (`attach image "{file}"`). Whoever or whatever supplies it (a workflow input, another agent's answer) can put text in a picture or words in a recording that read as instructions, and personal-data masking cannot see inside it, so it counts as reading untrusted content for the trifecta, as LA20 does for web pages; LA01 fires for the agent if the other legs are present. A file the script names (`attach image "receipt.png"`) does not fire it. An `extract` step is not an agent and is not reported, but it returns typed fields only | low | LLM01, LLM02 |
| LA24 | A workflow is named by an `expose` block, so other agents can call it. Every agent it reaches (through its own steps and the workflows it calls) counts as reading untrusted content, as web grounding does (LA20): the audit does not follow data, so it assumes they all see what a caller sends. The trifecta rule fires if they also reach private data and can send or act | low | LLM01 |
| LA25 | A `delegate` to a remote agent sends text, and attached files, to another organisation. The finding names the remote and says nothing in the script masks personal data first. Medium when the workflow reaches private data and no agent of it has a `pii` guard; a developer who masks in the transport or the channel accepts the finding in writing with that reason | low / medium | LLM02 |
| LA26 | An `agent_channel` has `audience: automatic`: code answers a party from outside with no person looking. High when a `remote` uses the channel, because that remote can ask for authorization, which code would then arrange | medium / high | LLM06 |
| LA27 | An `expose` has neither a budget of its own nor a run budget in the script, so whoever can call it decides what it costs | high | LLM10 |
| LA28 | A script can steer a started workflow: a `steer` statement, or a `supervise` rule that steers, puts words written in the script in front of a `steerable` agent of the child while it works, an input channel as LA18 is for a person. The finding names the agents it can reach and is medium when one of them also reaches private data and can send or act. Cancelling a child is not an input channel and gets no rule | info / medium | LLM01 |
| LA29 | A `supervise` block has a judged assertion: an evaluator is asked, on a cadence and at most `max` times, whether a sentence holds of a started workflow. The finding lists what the judge sees (facts only, which is info; or content with `seeing content`, which is low because that text may carry instructions) and what a fail can cause: only `steer`, `wrap_up` or `cancel use partial`, with text fixed in the script. A judge is a model's opinion and is not under the earned-autonomy ladder | info / low | LLM01, LLM06 |
| LA30 | A workflow **reads** a signal from another run: a `wait signal "name"` in a child, a `wait handle signal "name"` or a `when signal "name"` rule in a parent. The finding names the signal and the side that can send it (the parent workflow, or the child workflow). The text is written by another run and may carry instructions, so it is read as `unchecked`; the finding is **medium** when that text is written into a model's prompt or a task's argument, directly or through a copy (`set copy = text`, followed through a chain of copies), and info otherwise. A signal causes nothing by itself and can never become a steer's text. There is no rule for *sending* a signal: the parent already gets the child's result, so the channel is not new | info / medium | LLM01 |
| LA31 | A program runs without a sandbox: a `sandbox` with `isolation: process` runs as a child process of the run, with its user, files and network (only time and output are bounded). The finding names the tool, its programs (an interpreter such as `python3` or `sh` can run any command) and the secrets it is given, by name. `isolation: container` is the sandbox | medium; high when a secret is passed in | LLM06, LLM02 |
| LA32 | A workflow reaches a process it started: an `http` tool with `reaches:` calls the program a `sandbox` serves, on `127.0.0.1` and that program's one port, only while its lease lives. The finding names the tool and the sandbox that owns the port. There is no loopback switch | info | LLM06 |
| LA33 | A `sandbox` reaches the network: with `network: allow` it reaches the listed hosts through the egress proxy and nothing else, and a wildcard host (`*.example.com`) is called out as wide; with `network: open` it reaches any host. See [the sandbox's threat model](sandbox.md) | info / medium | LLM02, LLM06 |

The audit reads the script only. It cannot see what Java, host-registered and MCP tools do, operating-system and
database permissions, what is inside indexed documents or memory, or what agents actually produce. Use it with
`weave check`, code review and the rest of this guide.

---

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)
