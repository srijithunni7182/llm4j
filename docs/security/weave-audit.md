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
| LA02 | An effect with no approval: a shell tool running unattended, an outward tool, a local write; a writing `http` tool with no `allow_paths` | high / medium / low | LLM06 |
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

The audit reads the script only. It cannot see what Java, host-registered and MCP tools do, operating-system and
database permissions, what is inside indexed documents or memory, or what agents actually produce. Use it with
`weave check`, code review and the rest of this guide.

---

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)
