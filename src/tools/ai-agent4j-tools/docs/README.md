# ai-agent4j-tools documentation

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../../../docs/TOOLS_CATALOG.md).**

Ready-made, guarded tools for agents built with [ai-agent4j](../../../ai-agent4j/). The library overview and a Java quick start
are in the [module README](../README.md); this folder is the reference.

| Page | What it covers |
|---|---|
| [Concepts](concepts.md) | Options, secrets, where requests may go, the effect journal, how hosts plug in |
| [`webhook`](webhook.md) | Post a message to Slack, Discord, Teams or any endpoint |
| [`email`](email.md) | Send mail over SMTP, or write `.eml` files to an outbox |
| [`http`](http.md) | Call a REST API under a fixed base URL |
| [`file`](file.md) | Read, list, write and append text files in one directory |
| [`shell`](shell.md) | Run a few named programs, with no shell |
| [`sql`](sql.md) | Run read-only queries |
| [`workspace`](workspace.md) | Read and edit a directory: exact patches, search, snapshots to try again |
| [`sandbox`](sandbox.md) | Run allow-listed programs on a workspace, isolated in a container; keep one serving |
| [`compute`](compute.md) | Exact arithmetic, dates, a value out of JSON, statistics over CSV |
| [`verify`](verify.md) | A test report or a schema check, as counts a workflow can rely on |
| [`scan`](scan.md) | Find secrets and personal data, and say where, never what |
| [Writing a tool](writing-a-tool.md) | Add a tool to this library |
| [Safety model](safety.md) | What each tool defends against, and what it does not |

Every Java snippet on the kind pages is checked by `DocumentedExamplesTest`: the options in it must pass the kind's own `check`.
