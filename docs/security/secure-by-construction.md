# Secure by construction

[← Back to the README](../../README.md) · [Security guide](../../SECURITY.md)


An agent's next step is chosen by text, and some of that text comes from places you don't control: a web page, an
email, a tool's output. Any of it can try to give the agent orders, and no prompt reliably stops that. So llm4j
doesn't rely on the model to behave. **The model reasons; the code holds the authority.** What an agent may touch,
spend, send and decide is declared in Java or in a Loom script and enforced by the runtime on every call, whatever
the model says.

| The risk | What llm4j does about it |
|---|---|
| A web page tells the agent to run a command | Tools are declared **per agent**. The agent that reads the web needn't have a shell, and a `shell` tool runs only allow-listed programs, with no shell syntax, behind an approval. |
| The agent sends data somewhere it shouldn't | Webhooks have a fixed URL. `http` reaches only paths under one base URL and refuses internal and cloud-metadata addresses. `email` sends only to listed recipients. **Every way out can wait for a person's yes.** |
| A loop runs away with your money | **Budgets are checked before each model call.** Every loop and reasoning chain has a bound, and the script decides what happens at the limit. |
| A crash makes it send the same thing twice | Every effect is **journaled**. A resume, a retry or a rewind never repeats a send. |
| A secret leaks into a prompt, a log or an error | Credentials come from the environment, an encrypted file, or your own vault, and a literal in a script is a load error. Providers fetch them [per request](../../ai-agent4j/wiki/Secret-Store.md) and send them only to hosts you allow, and secrets are scrubbed from every result, trace and journal. |
| Customer data reaches a hosted model | `guard { pii: mask }` masks emails, phone numbers, card numbers and more in everything the agent sends, including tool results. |
| An agent is given too much freedom too soon | **Earned autonomy**: it starts by proposing while people decide, and moves up only when its measured record supports it. A ceiling, a freeze and fail-closed defaults stay in force. |
| Someone else answers your agent's questions | Questions reach your phone; only allowlisted users can answer, approvals need the question's code, and nothing listens on a port. |
| You can't tell what happened | `weave check` finds problems before anything runs. Audit logs and journals record every approval, refusal, level change and operator action, with the reason. |

These controls are tested like features: hostile-input suites, secrets sweeps, and **sabotage runs** that break each
guard on purpose to prove a test catches it.

**`weave audit`** reviews any Loom script before it runs. It maps each agent's reach, flags the "lethal trifecta" and
unapproved effects, and reports every finding against the **[OWASP Top 10 for LLM Applications](https://genai.owasp.org/llm-top-10/)**.
It exits non-zero on a high finding, so it can gate your CI.

👉 **[Read the security guide](../../SECURITY.md)**. It covers:
- the threat model, and what each building block does;
- a step-by-step way to secure a Loom workflow;
- a worked example that survives a prompt injection;
- a risk-by-risk mapping to the OWASP Top 10 for LLM Applications, **with the gaps named**;
- the patterns to avoid, and an honest account of what llm4j does *not* protect you from.

