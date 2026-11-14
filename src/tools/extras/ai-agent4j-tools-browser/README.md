# ai-agent4j-tools-browser

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../../../docs/TOOLS_CATALOG.md).**

The `browser` tool kind: open, read, click, fill and screenshot web pages in a Chrome or Chromium you have installed. Add
it when a page needs its scripts to show anything, when a workflow checks an app the [`sandbox`](../../ai-agent4j-tools/docs/sandbox.md)
serves, or when an approved step fills in a form. For a page that is plain HTML, the [`http`](../../ai-agent4j-tools/docs/http.md)
tool is lighter.

It talks to the browser over the Chrome DevTools Protocol with the JDK's own WebSocket client: no Playwright, no
Selenium, no dependency beyond the core, the light `ai-agent4j-tools` module and Jackson. **Verified with Chrome for
Testing 153** (headless, on Linux); other recent Chrome and Chromium versions use the same protocol calls.

## From a Loom script

```text
tool Web { use: browser  hosts: "example.com, *.example.com"  screenshots: "evidence"  timeout: "20s" }
agent Checker { model: "gemini-2.5-flash"  system: "Open the page, check the price shown, and take a screenshot as evidence."  tools: [Web]  approve: [Web] }
```

To check an app a sandbox serves, name the sandbox: `tool App { use: browser  reaches: Site }` loads
`http://127.0.0.1:<port>` of the program `Site` serves, on that one port and only while it runs.

## From Java

```java
Tool web = new BrowserKind().create("Web", Map.of("hosts", "example.com", "screenshots", "evidence"), Path.of("."));
web.execute(Map.of("action", "open", "url", "https://example.com/"));
String text = web.execute(Map.of("action", "text"));
```

## Options and arguments

| Option | Meaning |
|---|---|
| `hosts` | The hosts pages may load from, over https: `example.com`, `*.example.com` (subdomains), `host:port`. Required unless `reaches` is given |
| `reaches` | A sandbox whose served program the browser may load (D7's one-port grant) |
| `browser` | The browser program. Default: the first of `chromium`, `chromium-browser`, `google-chrome`, `google-chrome-stable`, `chrome` on the `PATH` |
| `chrome_sandbox` | `false` starts Chrome with `--no-sandbox`, which Chrome needs when it runs as root (in a container). Default `true`; leave it unless you must |
| `screenshots` | The folder screenshots are written to, inside the script's directory. Without it, `screenshot` is refused |
| `timeout` | Time for one action, default 30s, at most 2m. An agent can ask for less |
| `max_chars` | Most characters of page text returned, default 50000, at most 500000 |
| `max_requests` | Most requests one page may make, default 200, at most 2000 |
| `max_screenshot` | Largest screenshot, default 5m, at most 20m |

| `action` | Arguments | Result |
|---|---|---|
| `open` | `url`, `timeout` | `url` (where it landed), `title`, `status` |
| `text` | | the visible text, and `truncated` |
| `screenshot` | `path` (a `.png` in the screenshots folder) | `path`: the file, which the script passes on; never an image returned to the model |
| `click` | `selector` (CSS), or `text` (visible text or label) | `clicked` |
| `fill` | `selector` or `text`, and `value` | `filled` |
| `wait_for` | `selector` or `text`, `timeout` | `found` |
| `console` | | what the page wrote to its console |
| `failed_requests` | | the requests refused or failed, with the reason, and `refused`: dialogs dismissed, downloads and file pickers refused |

## What keeps it safe

- **Every request is checked, not just the first address.** The browser pauses every request a page makes
  (`Fetch.requestPaused`) and lets it through only when it is https to a listed host whose addresses are public, or the
  served program's one port. Everything else fails (`BlockedByClient`) and is listed in `failed_requests`. https also goes
  through an egress proxy that resolves each name once and refuses loopback, private and cloud-metadata addresses, so a
  name that answers differently the second time gains nothing; without `reaches`, loopback is sent to that proxy too and
  refused. With `reaches`, a page script's WebSocket to another loopback port is not paused by the protocol; keep pages you
  serve to ones you wrote.
- **A fresh profile** for each run, with no saved logins or cookies; it is deleted when the run ends, and a browser a
  crashed run left is stopped before a new one starts.
- **No downloads, no file pickers, no permission prompts;** dialogs are dismissed. Each is recorded.
- **Bounded:** time per action, page text, requests per page and screenshot size, with truncation said.
- **Page content is untrusted.** `weave audit` sees a tool that reads untrusted content and can send (a click or a filled
  form can send what the page asks); `text`, `title`, `console` and the lists stay unchecked. A click and a fill are
  journaled effects, so a resumed run does not click again. Put the tool in the agent's `approve:` list when a click can
  order, pay or post.
- **Isolation:** the browser runs as a process of the run (`isolation: none` in the audit), relying on Chrome's own
  sandbox. To run it in a container, start the container with its own Chrome and name it with `browser:`.

## Adding it

To a Maven project:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-tools-browser</artifactId>
    <version>6.2.0</version>
</dependency>
```

To `weave`: `weave run flow.loom --classes ai-agent4j-tools-browser-6.2.0.jar`, on a machine with Chrome or Chromium.
