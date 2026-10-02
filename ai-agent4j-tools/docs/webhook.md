# `webhook`

[← all tools](README.md)

## From Java

```java
Tool slack = new WebhookKind().create("Slack",
        Map.of("url", System.getenv("SLACK_WEBHOOK"), "format", "slack"), Path.of("."));
slack.execute(Map.of("text", "The digest is ready", "title", "Daily digest"));
```

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new WebhookKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Slack   { use: webhook  url: env.SLACK_WEBHOOK   format: slack }
tool Alerts  { use: webhook  url: env.DISCORD_HOOK    format: discord  retries: 3 }
tool Ingest  { use: webhook  url: env.INGEST_URL      format: json  "header.X-Source": "loom"  idempotency: true }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `url` | **Secret** (`env.NAME`): webhook URLs carry tokens. Required. |
| `format` | `slack` (default), `discord`, `teams` or `json` |
| `retries` | Extra attempts on a 429, a 5xx or a failure that sent nothing (default 2, at most 5) |
| `idempotency`, `on_unknown`, `timeout`, `hosts`, `allow_http`, `allow_private`, `header.*` | shared by every network tool: see [concepts](concepts.md#options-every-network-tool-takes) |

The agent gives `text` (required, at most 20,000 characters) and `title` (one line). A 429 is waited out as long as
the server asks (up to 30 s) and retried; a longer wait, or any other 4xx, ends the call with the status. A response
that never arrives is *not* retried inside the call, because the message may have been delivered: the call is left
as unknown for the resume rules in [the effect journal](concepts.md#the-effect-journal). The result is `Sent to slack webhook (HTTP 200).`; the URL is never shown.
The `teams` format sends an Adaptive Card message envelope, the shape Teams Workflows webhooks accept.
