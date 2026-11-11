# Security audit: finding the hole before an attacker does

[← Back to the README](../README.md)

`weave audit` reads a Loom workflow offline, free and with no API key, and exits non-zero so it can gate CI.

## The example

This agent reads the web, reads your customer files and can send mail. That is the *lethal trifecta*: text on a web page can tell it to mail your customers' data out.

```loom
tool Web   { use: duckduckgo }
tool Files { use: file  root: "customers"  mode: read }
tool Mail  { use: email  host: "smtp.example.com"  username: env.SMTP_USER  password: env.SMTP_PASSWORD
             from: "support@example.com"  to: "team@example.com" }

agent Helper {
    model: "any-model"
    system: "Research the customer's question and mail the team a summary."
    tools: [Web, Files, Mail]
}

workflow Main(question) {
    delegate "Research: {question}" to Helper -> summary
    return summary
}
```

```text
$ weave audit main.loom

4 findings: 1 high, 2 medium, 1 low
```

## The findings and their fixes

| Severity | Rule | Finding | The fix it prints |
|---|---|---|---|
| High | `LA01` | One agent holds the lethal trifecta with an open way out | Split the work: one agent reads the outside world, another touches your data, a third sends; connect them with `expecting { ... }` |
| Medium | `LA03` | No run budget | Add a run budget, e.g. `budget { tokens: 200000  calls: 150 }` |
| Medium | `LA02` | An effect with no approval | Let the agent propose, approve once at the edge, act with code: `use Mail(...) approve` |
| Low | `LA04` | Private data with no PII guard | Add `guard { pii: mask }` |

Each finding is mapped to the OWASP Top 10 for LLM Applications.

## How it compares

In the [probes](../probes/README.md#p11-a-reply-that-breaks-a-written-rule-can-it-leave-without-a-check), the same mistakes built on LangGraph, LangChain and Google ADK raised zero warnings. The probes use fake models and no keys, so you can rerun them.

## Further reading

- [Architecture you can review](REVIEWABLE_ARCHITECTURE.md): the thirteen mistakes and what `weave` prints for each
- [What a coding agent sees](REVIEWABLE_ARCHITECTURE.md#what-a-coding-agent-sees)
- [Security guide](../SECURITY.md)
- [Observability security signals](../wiki/Observability-Security-Signals.md)
