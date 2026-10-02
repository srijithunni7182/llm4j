# The OWASP Top 10 for LLM Applications, and the gaps

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)

How llm4j addresses each risk, what you need to do, and what is not covered yet. [`weave audit`](weave-audit.md) reports findings against the same list.

## Risk by risk

How llm4j addresses each risk in the [2025 list](https://genai.owasp.org/llm-top-10/), what you need to do, and what
is **not** covered yet. The gaps are stated plainly so that you can cover them yourself, and so that we know what to
build next.

| Risk | What llm4j does | What you do | Gaps |
|---|---|---|---|
| **LLM01 Prompt Injection** | Tools declared per agent and enforced on every call; typed hand-offs (`expecting`); approvals in front of effects; `weave audit` finds the trifecta (LA01). | Split the trifecta across agents; approve every way out. | No detection of injected instructions; no tracking of which values came from untrusted content; untrusted text is not marked off inside prompts. Mitigation is by architecture, not detection. |
| **LLM02 Sensitive Information Disclosure** | `guard { pii: mask \| block }` and `MaskingLLMClient`; secrets only from `env.NAME`, scrubbed from results, errors, traces, journals and audit logs; audit logs record kinds, not data; local embeddings. | Guard agents that see personal data; keep secrets in the environment. | PII detection is pattern-based: emails, phones, SSNs, cards and IP addresses, but not names, addresses or health data. No check on what leaves through a webhook or an email (no data-loss prevention). File-based indexes and ledgers are not encrypted. Telegram bot chats are not end-to-end encrypted. |
| **LLM03 Supply Chain** | Only declared tools, servers and skills exist; `weave check` lists them; OWASP Dependency-Check runs in CI; `weave audit` flags MCP servers, remote specs, skills and imports (LA08, LA14). | Review and pin MCP servers and Java tools; vendor remote specs and imports. | No pinning, hashing or signatures for MCP servers, OpenAPI specs, skills or `use: class` tools. Model provenance is the provider's. |
| **LLM04 Data and Model Poisoning** | Knowledge sources are declared; `weave audit` flags memory fed by untrusted content (LA09) and indexed sources (LA13). Earned autonomy and eval4j measure behaviour over time. | Index only sources you control; keep long-term memory off agents that browse. | No provenance, review or approval for what enters an index or long-term memory; a rewind does not undo saved facts. |
| **LLM05 Improper Output Handling** | Each tool checks the model's arguments against its fence: no shell syntax, no header or path injection, read-only SQL with bound parameters. `expecting` checks the shape of results. | Treat any agent output you render or forward as untrusted; escape it at the destination. | Results are put into later prompts as text, unescaped. Webhook text is not cleaned of chat mentions (such as `@channel`) or links. `expecting` checks shape, not content. Loom does not render HTML, so escaping is the consumer's job. |
| **LLM06 Excessive Agency** | Declared tools; `approve:` with journaled, per-call answers (from your phone, needing the code); `shell` must be approved or explicitly unattended; earned autonomy with a ceiling, blind measurement, freeze and fail-closed defaults; budgets; `weave audit` LA01, LA02, LA05–LA07, LA10–LA12. | Approve every outward tool; give tools the least reach; start decisions at `watch`. | Only `shell` *requires* approval. Email, webhooks and writing HTTP tools don't, unless you add it (the audit flags them). No per-tool call limits beyond email's `max_per_run`. In plain Java, `delegate_task` can hand a sub-agent anything in its registry. |
| **LLM07 System Prompt Leakage** | A credential can't be written into a script, so not into a prompt; authority lives in the runtime, not in prompt text. | Treat prompts as public: no secrets, and no rules whose secrecy matters. | No detection of attempts to extract a prompt. |
| **LLM08 Vector and Embedding Weaknesses** | Local embeddings (no data leaves); declared knowledge sources; pgvector relies on your database's permissions. | Keep one index per audience; control who can write to sources. | No per-user or per-tenant access control in retrieval; no poisoning detection; file-based indexes are unencrypted. |
| **LLM09 Misinformation** | eval4j judges groundedness, hallucination, correctness and task completion as a build gate; earned autonomy measures an agent against people, blind, before it may act; `weave audit` LA10. | Test agents with eval4j; keep a person in the loop until the record says otherwise. | No runtime check of answers against sources, and no citation enforcement. |
| **LLM10 Unbounded Consumption** | Budgets checked before every model call (run, agent, per call, per day); every loop and agent bounded; rate limits pause and resume; timeouts and size caps on tools; `weave audit` LA03. | Put a run budget on every script. | Embedding calls are not charged to budgets; tool calls are not counted (beyond email's `max_per_run`); replies arriving through a channel are not rate-limited. |

---

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md)
