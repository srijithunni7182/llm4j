# `http`

[← all tools](README.md)

## From Java

```java
Tool github = new HttpKind().create("Github", Map.of(
        "base_url", "https://api.github.com",
        "auth_header", "Authorization", "auth_value", "Bearer " + System.getenv("GITHUB_TOKEN"),
        "header.Accept", "application/vnd.github+json",
        "allow_paths", "/repos/*, /search/*"), Path.of("."));
github.execute(Map.of("path", "/repos/octocat/hello-world/issues", "query", Map.of("state", "open")));
```

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new HttpKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Github {
    use: http
    base_url: "https://api.github.com"
    auth_header: "Authorization"  auth_value: env.GITHUB_TOKEN
    "header.Accept": "application/vnd.github+json"
    allow_paths: "/repos/*, /search/*"
}
tool Status { use: http  base_url: "https://status.example.com"  methods: "GET, POST"  idempotency: true }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `base_url` | Required. The agent can only choose a path below it. |
| `methods` | Allowed methods: `GET` (default), `POST`, `PUT`, `PATCH`, `DELETE`. Anything else is refused. |
| `allow_paths` | Path patterns (`*` within a segment, `**` across). Default: everything below `base_url`. |
| `auth_header` or `auth_query`, with `auth_value` | Exactly one; `auth_value` is a **secret** |
| `header.*` | Fixed request headers |
| `max_bytes` | Response cap, default 64k |
| `follow_redirects`, `retries`, `idempotency`, `on_unknown`, `timeout`, `hosts`, `allow_http`, `allow_private` | shared by every network tool: see [concepts](concepts.md#options-every-network-tool-takes) |

The agent gives `path` (starts with `/`; no `..`, `//`, `@`, `?` or `#`), and optionally `method`, `query` (an
object) and `body` (a string, or an object sent as JSON; not for GET). The agent can't set headers or the host. The
result is the status line, the content type and the body; only text, JSON, XML and form responses are returned. A GET
is retried on a 429, a 5xx or a dropped connection; other methods only with `idempotency: true`. Use `approve:` on
the agent for any tool that allows a method other than GET.
