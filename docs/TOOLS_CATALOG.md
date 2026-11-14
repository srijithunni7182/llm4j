# 🧰 Tools catalog

Every ready-made tool llm4j ships, what it can do for your agent, and what stops a mistaken or hostile model from misusing it.

A model that can only talk is a chatbot. A model that can post to Slack, call an API, edit a codebase, run its tests and open a branch is a colleague. The catch is that **the model, not you, chooses the arguments**. Each tool here is built for that case: the limits are in the code, not in a prompt, and every limit is a test.

Fifteen tools in two groups: eleven **light tools** in one module that needs only the core and Jackson, and four **extras** you add only when you want them.

## 🗺️ At a glance

| | Tool | One line | Needs approval? |
|---|---|---|---|
| 📣 | [`webhook`](#-webhook) | Post to Slack, Discord, Teams or any JSON endpoint | No (fixed, secret URL) |
| 📧 | [`email`](#-email) | Send mail, to fixed or allow-listed recipients | Fixed recipients: no |
| 🌐 | [`http`](#-http) | Call a REST API under a fixed base URL | Yes, for any method but `GET` |
| 📁 | [`file`](#-file) | Read and write text files in one directory | Not for `read` |
| 🖥️ | [`shell`](#-shell) | Run a few named programs, with no shell | Yes, or `unattended: true` |
| 🗄️ | [`sql`](#-sql) | Read-only queries and schema lookup | No (reads only) |
| 🧮 | [`compute`](#-compute) | Exact arithmetic, dates, JSON and CSV figures | No (reaches nothing) |
| ✅ | [`verify`](#-verify) | A test report or schema check, as typed counts | No (runs nothing) |
| 🕵️ | [`scan`](#-scan) | Find secrets and personal data: where, never what | No (changes nothing) |
| 🛠️ | [`workspace`](#-workspace) | Read, patch, search and snapshot a directory | When it can write |
| 📦 | [`sandbox`](#-sandbox) | Run allow-listed programs on a workspace, isolated | Yes, or `unattended: true` |
| 🔎 | [`search`](#-search) | Web search through a provider you choose *(extra)* | No |
| 📄 | [`documents`](#-documents) | Text of PDF, DOCX and HTML, with page numbers *(extra)* | No |
| 🌿 | [`git`](#-git) | Status, diff, commit and an approved push *(extra)* | Yes, for a push |
| 🧭 | [`browser`](#-browser) | Drive a real Chrome, every request checked *(extra)* | Yes, when a click can order, pay or post |

## 🧱 What every tool gives you

These hold for every tool in this catalog, so the sections below don't repeat them.

- 🔒 **The model can't widen its own reach.** Hosts, paths, programs, recipients and methods are fixed by you. The model supplies only what you leave open.
- 🔑 **Secrets stay secret.** A key is given as `env.NAME` or `secret.NAME`, never as text in a script, and is scrubbed (plain, URL-encoded and Base64) from everything a tool returns, traces and errors included.
- 🧯 **A tool never throws at the agent.** A refusal comes back as text starting `Error:`, so the agent can react.
- 🌍 **Network tools can't be turned on your own network.** Loopback, private and cloud-metadata addresses are refused (SSRF), requests stay under the declared base URL, and TLS verification is never off.
- 📏 **Everything is bounded:** response sizes, output, rows, time, requests and retries.
- 📒 **Side effects are journaled.** A retry, resume or rewind after a crash recognises a send already made instead of sending it twice.
- 🔍 **`weave audit` sees them.** Each tool describes itself to the audit: what it reaches, what it changes and which of its results are untrusted text. See the [safety model](../src/tools/ai-agent4j-tools/docs/safety.md).

---

## 💬 Talk to the outside world

### 📣 `webhook`

**Tell your team when something happens.** Posts a message to Slack, Discord, Microsoft Teams or any JSON endpoint.

- Formats for `slack`, `discord`, `teams` (an Adaptive Card) and plain `json`.
- Waits out a `429` as long as the server asks, and retries a `5xx`.
- **Safe by design:** the webhook URL is a secret fixed in the script. The model supplies the text and an optional title, never the destination.
- Optional `idempotency` so a replay after a crash doesn't post twice.

```text
tool Slack { use: webhook  url: env.SLACK_WEBHOOK  format: slack }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/webhook.md)

### 📧 `email`

**Let an agent write to people without letting it write to anyone.** Sends mail over SMTP, or writes `.eml` files to an outbox so you can see what *would* go out.

- Recipients are **fixed** (`to:`) or chosen by the agent from an **allow-list** of patterns (`allow_to: "*@example.com"`).
- A cap on messages per run (`max_per_run`) that holds across a resume, because it is counted from the journal.
- Optional attachments from inside the base directory.
- **Safe by design:** no header injection, STARTTLS required by default, and if one recipient is refused nobody gets the message.
- `outbox:` mode needs no mail library or server: ideal for development and tests.

```text
tool Support { use: email  host: "smtp.example.com"  username: env.SMTP_USER  password: env.SMTP_PASSWORD
               from: "support@example.com"  allow_to: "*@example.com"  max_per_run: 5 }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/email.md)

### 🌐 `http`

**Plug an agent into any REST API.** Calls an API under a fixed base URL.

- Allowed **methods** and **path patterns** are yours (`allow_paths: "/repos/*, /search/*"`); the agent can't set headers or the host.
- Auth by header or query parameter, with the credential as a secret.
- Queries and JSON bodies are built for the agent; only text, JSON, XML and form responses come back, capped in size.
- Can reach a program a [`sandbox`](#-sandbox) serves, on that one port and only while it runs (`reaches:`), so an agent can test the app it just built.

```text
tool Github { use: http  base_url: "https://api.github.com"  auth_header: "Authorization"  auth_value: env.GITHUB_TOKEN
              allow_paths: "/repos/*, /search/*" }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/http.md)

### 🔎 `search` *(extra)*

**Give research agents the web, on your terms.** Web search through a provider you choose, key and pay for. The Brave Search API is the reference provider; another service is one small class.

- Results are `{title, url, snippet}`, bounded, and **always treated as untrusted text**, because a page can carry instructions.
- One host reached over HTTPS, and nothing else.
- Quotas and terms you have agreed to, instead of whatever a free scraper allows.

```text
tool Web { use: search  provider: brave  api_key: secret.BRAVE_SEARCH_KEY  max_results: 5 }
```

📖 [Module](../src/tools/extras/ai-agent4j-tools-search/README.md)

### 🧭 `browser` *(extra)*

**For pages that need their scripts to show anything, or an app you have just built.** Opens, reads, clicks, fills and screenshots pages in a Chrome or Chromium you have installed, over the DevTools Protocol with the JDK's own WebSocket client. No Playwright, no Selenium.

- **Every request the page makes is checked**, not just the first address: https to a listed host with public addresses, or the one port of a served program. Everything else fails and is listed in `failed_requests`.
- A fresh profile for each run: no saved logins or cookies, deleted when the run ends.
- No downloads, no file pickers, no permission prompts; dialogs are dismissed and recorded.
- Screenshots are written to a folder you choose and passed on as files, never as images fed back to the model.
- Clicks and fills are journaled effects, so a resumed run doesn't click twice.

```text
tool Web { use: browser  hosts: "example.com, *.example.com"  screenshots: "evidence" }
```

📖 [Module](../src/tools/extras/ai-agent4j-tools-browser/README.md)

---

## 🗂️ Work with data and files

### 📁 `file`

**A scratchpad an agent can't escape.** Reads, lists, writes and appends text files in one directory.

- `mode: read | write | readwrite`, and file-name patterns (`allow: "*.md, *.json"`).
- Writes are atomic and appends are serialised, so parallel branches never interleave lines.
- Refuses paths that climb out, links that lead out, hidden files (`.env`), the run's own journal and binary files.
- **There is no delete.**

```text
tool Reports { use: file  root: "reports"  mode: write  allow: "*.md, *.json" }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/file.md)

### 🗄️ `sql`

**Let an agent answer questions from your database, and never change it.** Runs read-only queries and looks up the schema.

- **Two layers:** the connection is opened read-only, and each statement is checked: exactly one `SELECT`, `WITH` or `VALUES`, with no writes and none of the functions that read files or change state.
- Values reach the database only through **bound `?` parameters**.
- Row, byte and time caps; results as a table, JSON or CSV.
- Give it a database user that is itself read-only: the checks guard against a mistake, and the account guards against the rest.

```text
tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  password: env.DB_PASSWORD  max_rows: 200  format: json }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/sql.md)

### 📄 `documents` *(extra)*

**Start a workflow from the paperwork people send you.** Extracts the text of PDF, DOCX, HTML and plain text files under a folder, with **the page or paragraph each part came from**, so an agent can cite "page 2".

- PDF through Apache PDFBox; DOCX and HTML with the JDK alone.
- Character offsets per page or paragraph: `text[start:end]` is exactly that part.
- Hostile files handled: scratch data in temp files, DOCX unpack size capped, no DTDs or external entities, encrypted PDFs refused with the reason.
- No OCR: a scanned PDF with no text layer gives empty pages, and says so.

```text
tool Docs { use: documents  root: "inbox"  max_chars: 200000 }
```

📖 [Module](../src/tools/extras/ai-agent4j-tools-documents/README.md)

### 🧮 `compute`

**Stop asking a language model to do arithmetic.** Exact calculation, dates, values out of JSON and statistics over CSV, from the arguments alone.

- **Exact decimals:** `0.1 + 0.2` is `0.3`, never `0.30000000000000004`. A division that doesn't end is rounded to 34 significant digits and says so.
- Actions: `calc`, `date` (add, between, weekday, today), `json` (pick a value by path) and `csv` (count, sum, mean, min, max, optionally grouped).
- **Deterministic:** `today` reads the run's clock, so tests with a fixed clock get a fixed date and a resumed run replays the same answer.
- Reaches nothing and changes nothing, so there is nothing for the audit to worry about.

```text
use Calc(action = "calc", expression = "{net} * 1.18") -> gross
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/compute.md)

### ✅ `verify`

**Turn "it looks fine" into a number you can gate on.** Reads a JUnit XML test report, or checks a JSON document against a JSON Schema, and returns one typed record: `status`, `total`, `passed`, `failed`, `errors`, `skipped` and the failing items.

- **A report it can't read is a failure, never a pass**, so a gate on `failed > 0` never lets through something it couldn't look at.
- A schema with a keyword it doesn't check is refused as a whole, because a check that skipped a rule would pass what it never looked at.
- Counts are taken test by test; a report with a `DOCTYPE` is refused.
- Gives an evaluation a typed result to score instead of text. It never runs a program.

```text
use Check(format = "junit", source = "test-report.xml") -> result
alt (result.failed > 0) { halt "{result.failed} tests failed" }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/verify.md)

### 🕵️ `scan`

**A gate before anything is sent or published.** Finds credentials and personal data in text or files, and says **where, never what**.

- Secret rules for known shapes (private keys, AWS, GitHub, Slack and Google keys, JWTs, `password = ...`) plus a high-entropy detector that doesn't flag a hex hash.
- Personal data: email, phone, SSN, credit card, IP address and URL, as you choose.
- **The matched text appears nowhere**, not in the result, journal, trace or an error, so a secret it finds never reaches a model's context.
- Versioned rules (`rules_version`), so a finding is reproducible. A net, not a proof: a clean result means nothing the rules know was found.

```text
use Scan(text = draft) -> found
alt (found.count > 0) { halt "The draft holds secrets or personal details; not sent." }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/scan.md)

---

## 💻 Build and run code

### 🛠️ `workspace`

**Edit, check, try again.** A directory an agent reads and edits: a report it revises, a configuration it changes, code it fixes.

- **Exact edits:** `patch` takes `{old, new}` pairs; each `old` must appear exactly once, and all edits apply or none do. An optional `version` hash catches a file that changed underneath.
- **Search:** `find` by glob, `grep` by text or regex, with context lines.
- **Snapshots:** `snapshot`, `restore` and `diff`, so a script can retry from a clean state (pair it with `rewind`).
- **Protected paths** (`protect: "tests/**"`) are read only through every action: a model can't edit the test that judges it.
- Never follows a link, never writes `.git`, never reads `.env*`, `*.pem`, `*.key` or `id_*`.

```text
tool Code { use: workspace  root: "project"  allow: "**"  mode: readwrite  protect: "tests/**" }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/workspace.md)

### 📦 `sandbox`

**Let a model run code without trusting it.** Runs a program from an allow-list, as a name and an argument list (no shell), on a workspace's files and nothing else of the machine.

- **Isolation is declared, with no default:** `container` (Docker or Podman; read-only root, every capability dropped, the workspace the only mount), `process` (for your own machine, flagged by the audit) or `vm` (a backend you plug in). It never silently falls back to a weaker one.
- **Network off by default.** `network: allow` with `hosts:` goes through an egress proxy that tunnels only to the listed hosts and refuses internal, loopback and metadata addresses.
- Limits on time, memory, processes, `/tmp` size and output (the start and end are kept, and the cut is counted).
- `serve` keeps a program running (a web app, say) so [`http`](#-http) or [`browser`](#-browser) can check it. It stops at its deadline, and after a crash.
- Protected paths are mounted read only, so tests can't be edited to pass.

```text
tool Tests { use: sandbox  workspace: Code  isolation: container  image: "python:3.12-slim"  allow: "python3"  timeout: "5m" }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/sandbox.md) · [Threat model](security/sandbox.md)

### 🖥️ `shell`

**A handful of safe commands.** Runs named programs, directly, with the arguments the agent gives.

- An **allow-list of program names**, never a path, and **no shell**: quoting, `;`, `|`, `>`, `$( )` and wildcards mean nothing.
- A cleared environment (only `PATH`, `LANG` and `TZ`, plus what you pass), output caps, and the whole process tree killed on a timeout.
- Interpreters and anything that runs other programs (`sh`, `bash`, `python`, `env`, `xargs`, `sed`, `ssh`, ...) are refused in the allow-list unless you say otherwise.
- A person must approve its calls, or the script says `unattended: true`.
- For anything beyond `df`, `du` and `ls`, prefer the [`sandbox`](#-sandbox).

```text
tool Ops { use: shell  allow: "df, du, ls"  timeout: 30s }
```

📖 [Reference](../src/tools/ai-agent4j-tools/docs/shell.md)

### 🌿 `git` *(extra)*

**Let a coding agent hand over its work as commits.** `status`, `diff`, `log`, `branches`, `branch`, `commit`, and a `push` only where you allow it, on a workspace's repository, run through the sandbox.

- **A repository's configuration runs nothing:** hooks, fsmonitor, the pager, the editor, external diffs and text conversion are all switched off, and a repository whose config defines a driver, a credential helper or a URL rewrite is refused before git runs.
- **A push goes only where the script says:** `push_url:` and `push_branches:` (for example `agent/*`) are yours. Git is given that URL, so a rewritten `.git/config` can't redirect the commits or the token.
- Never forced, a token never on a command line, and a commit, branch or push is a journaled effect, so a resumed run doesn't push again.

```text
tool Repo { use: git  workspace: Code  isolation: container  image: "alpine/git:2.47.2"  push: true  push_branches: "agent/*" }
```

📖 [Module](../src/tools/extras/ai-agent4j-tools-git/README.md)

---

## 🧩 Which tool for which job?

| I want an agent to... | Use |
|---|---|
| Notify a channel | 📣 `webhook` |
| Email a customer, safely | 📧 `email` (start with `outbox:`) |
| Talk to a SaaS API | 🌐 `http` |
| Research the web | 🔎 `search`, then 🧭 `browser` for pages that need scripts |
| Read invoices, contracts or reports | 📄 `documents` |
| Answer questions from a database | 🗄️ `sql` |
| Add up, convert or count exactly | 🧮 `compute` |
| Keep notes or write a report | 📁 `file` |
| Fix code until the tests pass | 🛠️ `workspace` + 📦 `sandbox` + ✅ `verify`, then 🌿 `git` |
| Check a web app it has just built | 📦 `sandbox` (`serve`) + 🧭 `browser` or 🌐 `http` (`reaches:`) |
| Make sure nothing leaks before it sends | 🕵️ `scan` before 📣 `webhook` or 📧 `email` |
| Validate a model's structured output | ✅ `verify` (`format: schema`) |

## ➕ Adding the tools to a project

The eleven light tools are one artifact:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-tools</artifactId>
    <version>6.2.0</version>
</dependency>
```

Each extra is its own artifact: `ai-agent4j-tools-search`, `ai-agent4j-tools-documents`, `ai-agent4j-tools-git` and `ai-agent4j-tools-browser`, so you pay for a dependency only when you want its tool. The module READMEs give the details, including the `--classes` jar to put on `weave`'s command line.

From Loom, a script needs no Java: `tool Name { use: <kind> ... }`. From Java, each kind has a `*Kind` class (`new HttpKind().create(name, options, baseDir)`) and a `check(options, baseDir)` that says what is wrong with the options without touching the network or the files.

## 🧱 Also built into the core

`ai-agent4j` carries a few simple tools of its own: a calculator, the current date and time, and web search through DuckDuckGo (no key) or SerpApi, with a cache and a fallback. It also turns an OpenAPI description or an MCP server into tools. See the [Creating Custom Tools](../src/ai-agent4j/wiki/Creating-Custom-Tools.md), [OpenAPI Tool](../src/ai-agent4j/wiki/OpenAPI-Tool.md) and [MCP Integration](../src/ai-agent4j/wiki/MCP-Integration.md) wiki pages.

## 📚 Go deeper

- [Ready-made tools: full reference](../src/tools/ai-agent4j-tools/docs/README.md): concepts, one page per tool
- [Safety model](../src/tools/ai-agent4j-tools/docs/safety.md): what each tool defends against, and what it does not
- [Writing a tool](../src/tools/ai-agent4j-tools/docs/writing-a-tool.md): add your own on the same contracts
- [The tool modules](../src/tools/README.md) and the [extras](../src/tools/extras/README.md)
- [Sandbox threat model](security/sandbox.md) and [`weave audit` rules](security/weave-audit.md)
- [Tutorial chapter 16: files, checks and running code](tutorial/16-files-checks-and-code.md)
