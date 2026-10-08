# `email`

[← all tools](README.md)

## From Java

```java
Tool mail = new EmailKind().create("Mail", Map.of(
        "host", "smtp.example.com", "port", "587", "security", "starttls",
        "username", System.getenv("SMTP_USER"), "password", System.getenv("SMTP_PASSWORD"),
        "from", "Digest <digest@example.com>", "to", "team@example.com"), Path.of("."));
mail.execute(Map.of("subject", "Daily digest", "body", "Three stories today..."));
```

Angus Mail (`org.eclipse.angus:angus-mail`) must be on the classpath for SMTP. The `outbox` mode needs nothing.

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new EmailKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Mail {
    use: email
    host: "smtp.example.com"  port: 587  security: starttls
    username: env.SMTP_USER   password: env.SMTP_PASSWORD
    from: "Digest <digest@example.com>"
    to: "team@example.com"                     // fixed: the agent can't change it
}
tool Support {
    use: email  host: "smtp.example.com"  username: env.SMTP_USER  password: env.SMTP_PASSWORD
    from: "support@example.com"
    allow_to: "*@example.com"                  // the agent chooses, within this list
    max_per_run: 5
}
tool DryRun { use: email  outbox: "outbox"  from: "digest@example.com"  to: "team@example.com" }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `host`, `port` | The SMTP server. Port 587 for `starttls`, 465 for `ssl`. `host` isn't needed with `outbox`. |
| `security` | `starttls` (default, required: a server that doesn't offer it is refused), `ssl`, or `none` (only for `localhost`, or with `allow_insecure: true`) |
| `username`, `password` | Both or neither; `password` is a **secret** |
| `from` | Required |
| `to` | Fixed recipients. Give exactly one of `to` or `allow_to`. |
| `allow_to` | Addresses the agent may choose (`a@x.com`, `*@x.com`) |
| `cc`, `bcc` | Fixed extra recipients |
| `max_recipients` | Default 20 |
| `max_per_run` | Messages per run, default 20; counted from the journal, so it holds across a resume |
| `attachments` | `true` lets the agent attach files inside the base directory (up to 10 MB in all) |
| `outbox` | A directory: each message is written as an `.eml` file and **nothing is sent**. For development. |
| `on_unknown`, `timeout` | see [concepts](concepts.md) |

The agent gives `subject` (one line, at most 200 characters), `body` (plain text, at most 200 KB), and, with
`allow_to`, `to`. `html: true` sends the body as HTML. Line breaks in an address or subject are refused, recipients
are checked exactly as sent, and if one recipient is refused nobody gets the message. A connection lost after the
message data was sent leaves the outcome unknown. Provider APIs and OAuth (Gmail API, Graph) are not covered; use
an MCP server for those.
