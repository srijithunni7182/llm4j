# Test Strategy

> **Layout update.** The tools were later moved out of Loom into the `ai-agent4j-tools` library (package
> `io.github.llm4j.tools`; see design.md §9). Where this document says `io.github.llm4j.loom.tools.generic` or
> `loom/ai-agent4j-loom/.../generic`, read the tools module for the tools, guards and their tests, and Loom for the
> executor, parser, CLI and guide tests.

[`verification.md`](verification.md) lists *what* must be checked. This document says *how* the checks are
built, where they run, and how we know nothing is missed. It follows the habits of the existing Loom
tests: JUnit 5, AssertJ, MockWebServer, `@TempDir`, opt-in switches such as `-Dloom.realCrontabTest=true`
with `assumeTrue`, and the JaCoCo gate the Jenkins pipeline already runs.

## 1. What is at risk

These tools can do things in the world. The strategy is weighted by what a failure costs:

| Risk | Example | Weight |
|---|---|---|
| A secret leaks (to a model, a log, a result) | webhook URL echoed in an error | Highest |
| A model reaches what it shouldn't | SSRF to `169.254.169.254`; `../` out of the directory; `; rm -rf` | Highest |
| An action repeats or is lost after a crash | the digest is mailed twice | High |
| Data is changed that shouldn't be | a "read-only" SQL call writes | High |
| A tool misbehaves under load | output fills memory; a hung server blocks a run | Medium |
| A tool is awkward to use | the description doesn't tell the model its arguments | Lower |

So guard code (`NetPolicy`, `SqlGuard`, path checks, `Redactor`, `EffectTool`, address handling) gets the
deepest testing: unit tests, generated inputs, and a hostile-model suite. Plain plumbing gets ordinary
tests.

## 2. Layers

| Layer | What it tests | Stand-ins | Runs |
|---|---|---|---|
| **L0 Unit** | Pure logic: `Options`, `Redactor`, `Limits`, `SqlGuard`, `PathMatcher`, `NetPolicy` (with a stub resolver), `BodyFormat`, the address and path validators | None, or tiny fakes | Every build |
| **L1 Component** | One kind end to end, with real I/O against a fake outside world | MockWebServer, GreenMail, H2, `@TempDir`, real child processes | Every build |
| **L2 Workflow** | A kind inside `HarnessExecutor`: scripted model → agent → tool, with approvals, guards, budgets and the real journals (memory, file, JDBC) | Scripted `LLMClient`, the same fakes as L1 | Every build |
| **L3 End to end** | The sample digest script run through the `weave` entry points, including `schedule sync` and `triggers install` (dry) | MockWebServer for the public API, outbox mode for mail | Every build |
| **L4 Live** | Real Slack/Discord/Teams, SMTP, GitHub, PostgreSQL | Accounts and keys | Opt-in: `-Dloom.live=true` plus the keys; skipped (`assumeTrue`) otherwise |

L0–L3 need no network and no accounts. They run under `mvn test` in the module, in the "Unit Tests &
Coverage" stage that already exists. L4 tests are tagged `@Tag("live")` and skip themselves, the same way
`SystemTriggerTest` skips the real crontab.

**Budget.** The new tests should add under 60 s to the module's run. So: no real sleeps over 1 s (retries and
`Retry-After` go through an injected sleeper, §3), MockWebServer reused per class, and the 50 MB-output
check streams from a generator rather than a file.

## 3. Seams the design must provide

Several checks are impossible without these. Each is part of the design (§7) and the tasks.

| Seam | Used by | Production default | Test double |
|---|---|---|---|
| `NetPolicy.Resolver` (`List<InetAddress> resolve(String)`) | V3.2, V3.3 | `InetAddress.getAllByName` | A map of host → addresses, counting lookups, able to change answers on the second call (rebinding) |
| `Sleeper` and `Clock` (the executor already has `setSleeper`/`setClock`) | retries in `HttpSupport`, `Retry-After`, timeouts | real | Records requested sleeps and returns at once, so `Retry-After: 30` is asserted, not waited for |
| `EffectContext` | every side-effect kind, V1.11, V2 | the executor's | `EffectContext.recording()` capturing audit and trace events; `noop()` |
| **Fault-injecting journal** | V2.2–V2.4 | n/a | `FaultJournal(RunJournal delegate, int failAfterPuts)` throws `SimulatedCrash` after the Nth `put`, so a test stops "between `pending` and `done`" and then re-runs with the same delegate |
| `EmailSender` (an interface behind `EmailKind`) | V5 unit tests | the Angus Mail implementation | An in-memory sender for L0; GreenMail for L1 |
| Reserved paths in `EffectContext` | V7.3 | the journal and trigger directories | A temp directory registered as reserved |
| A scripted `LLMClient` DSL | L2, L3, hostile suite | n/a | `ScriptedModel.calls(tool("Slack", args…)).thenAnswer("done")`; exists in similar form in earlier Loom tests and is reused, not rewritten |

## 4. Layer-by-layer approach

### L0 Unit

- **Table-driven.** Each guard has a table of `(input, expected)` rows in a `@ParameterizedTest`, kept next
  to the code so a reviewer sees every accepted and refused form. The tables are the V2/V3/V9/V6 lists in
  `verification.md`.
- **Pure.** No sockets and no files in L0. `NetPolicy` uses the stub resolver; `SqlGuard` takes a string.

### L1 Component

- **Real I/O, fake world.** HTTP kinds talk to MockWebServer over a real socket (so TLS-off loopback paths,
  redirects, and slow bodies are real). `email` talks to GreenMail over real SMTP. `file` uses a real
  temp directory with real symlinks. `shell` starts real processes. `sql` uses H2 in PostgreSQL mode.
- **Failures are simulated by the fake**, not by mocking our own code: a MockWebServer that stalls
  (`SocketPolicy.NO_RESPONSE`), drops (`DISCONNECT_AT_START`, `DISCONNECT_DURING_RESPONSE_BODY`) and
  answers 429 with `Retry-After`. A tiny socket server in the test sources drops an SMTP session after
  `DATA`. A shell fixture that sleeps, floods stdout, and spawns a child.
- **Echo servers for secrets.** One MockWebServer dispatcher echoes the request line, headers and body in
  its response and in error bodies; V1.5 runs every HTTP kind through it and greps every output channel
  for the secret.

### L2 Workflow

- Scripts are real `.loom` text, loaded through `LoomLoader`, so validation, approvals and guards run as
  they do in production.
- **Crash tests** run a workflow twice on one journal: the first run is cut off by `FaultJournal` or by
  throwing from the mock after the tool acted; the second run resumes. The assertion is always on the
  outside world (the mock's request count, the outbox's file count, the file's content) plus the journal
  keys, not on internal calls.
- **Journal matrix.** The V2 checks run on the in-memory, file and JDBC (H2) journals using a
  parameterised factory.
- **Approvals.** A `HumanInterface` fake answers yes/no/pause; V8.11 and the "approved call is journaled"
  behaviour are asserted at this layer.

### L3 End to end

The digest sample is run by a test the way a user would: a scripted model standing in for Gemini, a
MockWebServer standing in for the public API, the outbox for mail. Two "days" are simulated with the test
clock to prove the state file carries over. `weave schedule sync` and `weave triggers install` (no
`--apply`) are run through the CLI entry points, and their output is compared with the commands printed in
the guide. This is what keeps the documentation honest (V10.7).

### L4 Live

Four short tests (L1–L4 in `verification.md`), each skipped unless `-Dloom.live=true` and its keys are set.
They are run before a release and by hand when a provider changes. They are the only place the `teams`
envelope and a real SMTP handshake are checked.

## 5. Generated and negative tests

Hand-written lists miss cases in exactly the code that most needs to be right. Five parsers/guards get
generated-input tests, **deterministic** (a fixed seed, printed on failure so a failure replays) with
**2,000 iterations** by default and `-Dloom.fuzz.iterations=N` for longer runs (a nightly job). No new
dependency: `java.util.Random(seed)` and JUnit `@RepeatedTest`-style loops.

| # | Target | Generator | Property |
|---|---|---|---|
| F1 | `SqlGuard` | Statements built from a grammar of keywords, identifiers, string literals, comments and dollar-quoted text, with each forbidden keyword inserted in each context (bare, in a string, in a comment, in an identifier, after a `;`) | A statement is accepted **iff** its out-of-quote token stream satisfies the rules. A reference implementation (a slow, obviously-correct tokenizer in the test) must agree on every generated input. |
| F2 | Path validator (`http`) and `SafePaths`/`permit` (`file`) | Strings over the alphabet `a . / \ @ : % \0 \n`, percent-encodings of those, Unicode look-alikes (`．．／`), long runs | An accepted path, after normalisation, stays under its base, has no `..` segment, no scheme or host, and no control character. A refused path never touches the filesystem or network (asserted with a spy). |
| F3 | Email addresses and headers | Subjects, names and addresses with CR, LF, U+2028/2029, NEL, `<>`, quotes, very long strings | Every accepted message, written out and parsed back, has exactly the expected header lines: no injected `Bcc:`/`To:`, and the recipient set equals what was asked for. |
| F4 | `Redactor` | Random secrets (4–64 chars, including regex and URL metacharacters) placed in random text in plain, URL-encoded and Base64 forms, and split across a cut boundary | The output contains none of the forms. Text that holds no secret is returned unchanged. |
| F5 | `Options` | Random duration/size/list strings | Valid forms round-trip to the expected number; invalid ones give a load error and never throw. |

**Mutation check (manual, at review).** For the guard classes, deliberately break one rule at a time (for
example drop the IPv4-mapped IPv6 unmapping in `NetPolicy`, or the `INTO` keyword in `SqlGuard`) and confirm
a test fails. This is done once per guard when it is written, not in CI; the broken-rule list is recorded in
`verification.md`'s results.

## 6. The hostile-model suite (security regression)

A scripted model that **actively attacks** each tool, run at L2 so the real validation, approval and
effect machinery is in the path. Each tool has a fixed list of attacks; the assertion is the same for all:

> the call returns an `Error:` (or is refused at load), **and** the outside world shows no change: the mock
> saw zero requests, the file's hash is unchanged, no process ran, the database is unchanged.

| Tool | Attacks (non-exhaustive; the full list is the test's table) |
|---|---|
| `webhook` | a URL argument; a 301 to `http://169.254.169.254/`; a host that resolves private; a 10 MB `text`; a header-injection title (`x\r\nHost: evil`) |
| `http` | `path: "https://evil.example/"`, `//evil`, `/a/../../etc/passwd`, `/x?y=1#@evil`; a `Location` redirect to a private address; `method: "DELETE"` when not allowed; a header argument |
| `email` | `to: "a@evil.com"` outside `allow_to`; CRLF `Bcc:` in subject; 500 recipients; an attachment `../../.env` |
| `file` | `../x`, absolute paths, symlink out, `.env`, `.loom-triggers/…`, the journal directory, writing to a `read` tool, overwriting without `overwrite` |
| `shell` | `program: "/bin/sh"`, `"sh"`, `"../x"`; args `["; rm -rf /"]`, `["$(id)"]`, `["`id`"]`, `["a\nb"]`; a flood; an infinite loop |
| `sql` | `INSERT`, `DROP`, `SELECT 1; DELETE`, `SELECT … INTO`, a CTE with `DELETE`, a Unicode-escaped keyword, `COPY … TO PROGRAM` |

Each attack also asserts the **secrets rule**: the refusal text, trace and audit contain no secret.

## 7. Concurrency tests

`parallel` blocks and `for each` run tools from several threads, so:

- **Same tool, 32 threads, one executor.** Webhook (distinct bodies) → exactly 32 distinct requests, 32
  effect records. Same body → 32 requests (the `#n` suffix), and a replay returns 32 recorded results in
  order.
- **Appends** from 8 threads × 200 lines produce 1,600 whole lines (V7.6).
- **`max_per_run` under contention**: 50 threads against `max_per_run: 20` send exactly 20.
- **Runs under `-Dsurefire.runOrder=random` and repeated** (`@RepeatedTest(20)` on the concurrency tests)
  to flush flakiness.

## 8. Coverage and exit criteria

- **Coverage.** The new package `io.github.llm4j.loom.tools.generic` meets the module's JaCoCo threshold
  (the Jenkins stage already runs `jacoco:report`; the Loom module gains the same `jacoco:check` rule the
  eval4j module has, at **80% lines**) and the guard classes (`NetPolicy`, `SqlGuard`, `PathMatcher`,
  `Redactor`, `EffectTool`, the path/`permit` code, the address validator) at **90% branches**, enforced by
  a per-class rule.
- **Traceability.** Every acceptance criterion is mapped to at least one check in §9. A criterion with no
  check is a defect in the spec.
- **Done means:**
  1. L0–L3 and the fuzz suite pass, twice in a row, on a clean checkout.
  2. The hostile-model suite passes with every attack recorded in the results.
  3. Coverage gates pass.
  4. The existing Loom and ai-agent4j suites pass unchanged (nothing in earlier behaviour moved).
  5. The mutation list in §5 has been run for each guard.
  6. L4 has been run at least once for the live-only items, or its absence is recorded as a known gap.

## 9. Traceability: requirement → checks

| Requirement | Checks |
|---|---|
| R1.1 registered | V1.1 |
| R1.2 load validation | V1.2, V1.3, V5.10, V7.5, V8.2, V8.6, V9.8 |
| R1.3 secrets | V1.3, V1.5, V4.5, F4, hostile-suite secrets rule |
| R1.4 option forms | V1.2, F5 |
| R1.5 headers | V1.4, V4.6 |
| R1.6 description | V1.8 |
| R1.7 errors are results | V1.6 |
| R1.8 limits | V1.7, V3.7, V7.2, V8.8, V9.5 |
| R1.9 works with guards, approvals, budgets | V1.9, V1.10, V8.11 |
| R1.10 audit and trace | V1.11 |
| R1.11 no deletions | V1.12, V7.3 |
| R2 effect journal | V2.1–V2.11, concurrency §7 |
| R3 egress | V3.1–V3.7, V6.9, F2, hostile `webhook`/`http` |
| R4 webhook | V4.1–V4.8 |
| R5 email | V5.1–V5.12, F3, hostile `email` |
| R6 http | V6.1–V6.10, F2, hostile `http` |
| R7 file | V7.1–V7.7, F2, hostile `file` |
| R8 shell | V8.1–V8.13, hostile `shell` |
| R9 sql | V9.1–V9.9, F1, hostile `sql` |
| R10 docs/tooling/sample | V10.1–V10.7 |
| NFR dependencies / lazy loading | V5.9, V9.8 |
| NFR thread safety | §7 |

## 10. Platform notes

- **POSIX is the target** for `shell` (the box in the use case is Linux, and the installers cover
  systemd/launchd/cron). `shell` tests use `@EnabledOnOs({LINUX, MAC})`; on Windows they skip, and the
  loader refuses `use: shell` with a clear message rather than behaving differently (see design §7.4).
- **Symlink tests** skip where the OS can't create them (`assumeTrue` on a probe).
- **`file` and `sql`** tests are portable. File name matching is case-sensitive on every OS.
- **Time zones and locale**: tests set `-Duser.language=en -Duser.timezone=UTC` through surefire so
  `Retry-After` dates and table rendering don't vary.

## 11. Where the tests live

`loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/generic/`, split by kind and layer
(`webhook/`, `email/`, `http/`, `file/`, `shell/`, `sql/`, `foundation/`, `effects/`, `fuzz/`,
`hostile/`, `sample/`), following the package-per-feature layout of `parity/`, `depth/` and `resume/`.
Shared fakes (`ScriptedModel`, `FaultJournal`, `StubResolver`, `EchoServer`, `RecordingEffects`) are in
`generic/support/`. The sample's test resources are under `src/test/resources/docs/` with the other
parse-checked scripts.
