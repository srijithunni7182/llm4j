# ai-agent4j-tools

Ready-made, guarded tools for [ai-agent4j](../ai-agent4j/) agents. They are written for the case where the model, not you,
chooses the arguments, so each one limits what a hostile or mistaken model can do.

| Tool | What it does | What limits it |
|---|---|---|
| [`webhook`](docs/webhook.md) | Posts a message to Slack, Discord, Teams or a JSON endpoint | The URL is fixed and secret; the model supplies only the text |
| [`email`](docs/email.md) | Sends mail over SMTP, or writes `.eml` files to an outbox | Fixed recipients or an allow-list of patterns; no header injection |
| [`http`](docs/http.md) | Calls a REST API | A fixed base URL, an allow-list of paths and methods; no private or metadata addresses |
| [`file`](docs/file.md) | Reads and writes text files in one directory | Stays inside the directory; no hidden files; writes are atomic |
| [`shell`](docs/shell.md) | Runs named programs | An allow-list of programs, no shell, a cleared environment, output and time caps |
| [`sql`](docs/sql.md) | Runs read-only queries | One `SELECT`, `WITH` or `VALUES` statement, bound parameters, row and byte caps |

Every tool returns text, never throws at the agent, and scrubs its credentials (plain, URL-encoded and Base64) from
everything it returns. A tool with side effects is **journaled**: it records that a call is about to happen and that it
happened, so a repeat of the same call after a crash is recognised instead of sent twice.

Full reference: **[docs/README.md](docs/README.md)** (concepts, one page per tool, the safety model, writing a tool).

## Using a tool from Java

```java
Tool slack = new WebhookKind().create("Slack",
        Map.of("url", System.getenv("SLACK_WEBHOOK"), "format", "slack"),
        Path.of("."));
String result = slack.execute(Map.of("text", "Digest is ready"));
```

`create` takes the same options a script would write (`url`, `format`, `timeout`, `retries`, ...), already resolved.
`kind.check(options, baseDir)` returns what is wrong with them, or `null`, without touching the network or the files.
The other kinds are `EmailKind`, `HttpKind`, `FileKind`, `ShellKind` and `SqlKind`.

Register the tool with an agent the way you register any `Tool`.

### Surviving a restart

`create(name, options, baseDir)` journals in memory, which is right for one process. For "never send the same
message twice, even across a crash", pass an `EffectContext` whose `journal()` is backed by something durable:

```java
Tool slack = new WebhookKind().create("Slack", options, baseDir, myContext);
```

`EffectContext` is a small interface in `ai-agent4j` (audit, trace, journal, current step, clock, sleeper);
`EffectContext.noop()` is a starting point. [Loom](../loom/ai-agent4j-loom/) is one host that supplies a durable one, from its run journal.

## From a Loom script

Loom registers these kinds, so a script needs no Java:

```text
tool Slack { use: webhook  url: env.SLACK_WEBHOOK  format: slack }
```

See the Generic Tools section of the [Loom guide](../loom/ai-agent4j-loom/LOOM_GUIDE.md#generic-tools).

## Where the contracts live

The interfaces are in the core library, `ai-agent4j`, package `io.github.llm4j.agent.tool`: `ToolKind` (a kind built from string
options), `Effectful` (a tool with side effects), `EffectContext`, `EffectJournal`, `EffectPolicy` and `Outcome`. This module
implements them, so another library can provide its own kinds, or its own journal, without depending on this one.

## Dependencies

`ai-agent4j` (the `Tool` interface and OkHttp) and Jackson. Angus Mail is **optional**: add
`org.eclipse.angus:angus-mail` yourself to use `email` with SMTP (the outbox mode needs nothing). A JDBC driver for your
database is yours to add for `sql`. The mail library is only loaded when an `email` tool is built.

## Tests

`mvn verify` runs the tests (about 380, covering every tool, every guard, the failure paths, hostile calls, generated inputs,
concurrency, a plain-Java use with no Loom, and the documentation examples) and the coverage gates: 80% of lines overall, and 90% of branches in the classes that
decide what a model may do (`NetPolicy`, `SqlGuard`, `RequestPath`, `Redactor`, `EffectTool`, `PathGuard`,
`MailAddress`). The test helpers (an echo server, a scripted SMTP server, a recording context) are published as a
test jar.

## Adding a tool

See [Writing a tool](docs/writing-a-tool.md).
