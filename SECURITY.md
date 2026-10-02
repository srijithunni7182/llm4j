# Security in llm4j

**Treat the model as untrusted. Put the authority in code.**

An agent is a program whose next step is chosen by text, and some of that text comes from places you don't control:
a web page, an email, a document, a tool's output. Any of it can try to give the agent instructions, and today's
models still follow such instructions some of the time. No prompt fixes that.

llm4j is built on that assumption. What an agent may touch, spend, send and decide is set in code and in Loom
scripts, and the runtime enforces it on every call, whatever the model says. The model reasons inside a fence it
cannot see or move.

## The security guide

| Read | For |
|---|---|
| [The building blocks](docs/security/building-blocks.md) | What each module does for security, and how to use it: approvals, budgets, PII masking, audit, the hardened tools, and Loom's declared tools, guards, journal, rewind policy, earned autonomy and phone answers |
| [Securing a Loom workflow](docs/security/securing-workflows.md) | Nine steps with examples, a worked example that survives a prompt injection, and the patterns to avoid |
| [Auditing a script: `weave audit`](docs/security/weave-audit.md) | The static security review: what it finds (14 rules), the report, and how to gate CI on it |
| [The OWASP Top 10 for LLM Applications, and the gaps](docs/security/owasp-llm-top-10.md) | Each of the ten risks: what llm4j does, what you do, and what is not covered yet |
| [The tools' safety model](ai-agent4j-tools/docs/safety.md) | What a hostile call to `webhook`, `email`, `http`, `file`, `shell` or `sql` tries, and what stops it |

## The threat model

**Trusted:**
- the Java code you write;
- the Loom scripts you write and review;
- the machine and the environment variables they run with;
- the operator who runs the `weave` commands.

**Untrusted:**
- everything the model produces: its plans, its tool arguments, its answers, its "reasoning";
- everything the model reads: web pages, search results, emails, files, API responses, the output of other agents, and any text a user typed.

The dangerous combination is sometimes called the **lethal trifecta**. An agent is at real risk when it has all three of:

| | | Examples |
|---|---|---|
| 1 | **Access to private data** | your files, a database, credentials, email |
| 2 | **Exposure to untrusted content** | web search, inbound mail, uploaded documents |
| 3 | **A way to send data out** | HTTP calls, webhooks, email, shell commands |

With all three, a hostile page can tell the agent to read something private and send it somewhere. Most of what
follows is about **never giving one agent all three**, and about putting a person or a hard limit in front of the
third.

## Principles

1. **Authority lives in the script, not the prompt.** Tools, approvals, budgets and limits are declared in code or
   in a `.loom` file and enforced by the runtime. Nothing in a prompt or a model answer can widen them.
2. **Least privilege, per agent.** Each agent gets only the tools it needs, and each tool only the reach it needs:
   one directory, a few programs, one base URL, one recipient list.
3. **Effects are gated, journaled and never repeated.** Anything that changes the world can need a person's yes.
   Every effect is recorded, so a crash, a resume or a retry never sends the same thing twice.
4. **Spend is bounded before the call.** Budgets are checked before each model call, not discovered on the invoice.
5. **Secrets never become text.** Credentials come only from the environment. They never appear in a prompt, a
   result, an error, a trace, the audit log or the journal.
6. **Fail closed.** A missing approver blocks the call. An unreadable ledger means the lowest autonomy. A channel
   with no allowlist refuses to start. An unknown tool is held, not run.
7. **Check before running, record while running.** `weave check` finds problems before any model is called. The
   audit log and the journal say exactly what happened.

## In one minute

- **Split the trifecta.** No agent should read the outside world, touch your data *and* be able to send it out. Connect agents with typed results (`expecting { ... }`).
- **Least reach.** One directory, a few programs, one base URL, fixed recipients, a read-only database user.
- **Approve every way out.** `approve: [Tool]`, answered at a console or [from your phone](loom/ai-agent4j-loom/LOOM_GUIDE.md#answering-from-your-phone).
- **Bound everything.** A run budget on every script; small `max_iterations` on agents that read untrusted content.
- **Let autonomy be earned.** Start decisions at `watch`, keep a ceiling, and raise it on evidence.
- **Check it.** `weave check` and `weave audit` in CI; review `.loom` files like code; test refusals with eval4j.

## What llm4j does not do

Being clear about the limits is part of the security model:

- **It does not make models immune to prompt injection.** It limits what an injected instruction can achieve. A
  workflow that gives one agent all three legs of the trifecta is unsafe in llm4j too.
- **It cannot judge content.** A webhook will post a harmful message the model wrote. Approvals and eval4j tests
  are how you catch that.
- **It does not replace least privilege at the operating-system level.** Run Loom as a user, with database
  accounts and API credentials that can do no more than the workflow needs.
- **It does not vet third-party code.** MCP servers, `use: class` tools, `.loot` mappings and Java tools run with
  your process's permissions.
- **It does not certify compliance.** It produces evidence (audit logs, journals, ledgers) that helps you show
  what happened.
- **It has not had an external security audit.** The safety features are tested as described below, and the
  project has not yet been reviewed by an independent security firm.

## How the safety features are verified

The safety features are tested as deliberately as the features themselves:

- **Hostile-call suites** for every generic tool and for decisions and channels. A scripted model or person tries
  to escape the fence; each attempt must fail with no effect.
- **Sabotage runs.** Each guard is broken on purpose in the real source (ignore the allowlist, show the proposal in
  `watch`, let a replay perform an effect, log the token, ...) and a named test must fail. Every feature's gate
  requires all of them to be caught.
- **Secrets sweeps.** Runs with a recognisable fake secret, then every file, log, trace and journal is searched for
  it.
- **Real-process runs** against stand-in servers, including `kill -9` in the middle of a run, then resume.
- **The audit itself is tested**: each rule against a script that should trigger it, and the worked example in this
  guide against all of them.

The evidence for each feature is in `.kiro/specs/<feature>/evidence/`:
- `G3-sabotage.md`: the sabotage runs;
- `G9-safety.md`: the safety checks and review;
- `SIGN-OFF.md`: the gate results.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Contact the maintainer,
[@srijithunni7182](https://github.com/srijithunni7182), privately. Include:
- what you found;
- how to reproduce it;
- which module and version it affects.

You'll get an acknowledgement, and a fix or a plan, as soon as possible.
