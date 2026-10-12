# Design: Invoker Credentials for Loom Tools

**Status:** draft for review. Nothing in this spec is implemented.

## Problem

A web application logs a user in with OAuth and gets an access token (and a refresh token). A Loom workflow run for that
user has an agent whose tool (an OpenAPI tool) must call a downstream API **as that user**. Today there is no way to hand the
token to the tool:

- `tool Refunds { use: openapi … auth_value: env.X }` is resolved **once, when the tool is created** (`ToolFactory.create` →
  `resolve`), and the tool holds a plain `String`. `HarnessExecutor.createdTools` caches the tool for the life of the executor.
  So one executor can only act as one identity, and a token that expires mid-run cannot be replaced.
- The workaround is one `HarnessExecutor` per request with a tool registered from Java. It works, but it repeats
  `initialize()` for every request, and it cannot refresh a token inside a long or resumed run.

## Goals

1. A script can say that a tool's credential comes **from whoever is running the workflow**: `auth_value: invoker.token`.
2. The host supplies it as a **`InvokerCredentials` object it implements** (a supplier). Loom calls it **for every request**,
   so the host's implementation can cache, and use a refresh token to get a new access token when the old one expires.
3. The credential is **run-scoped**: two runs started by different users at the same time never see each other's credential,
   on any thread, including parallel branches, broadcasts and step timeouts.
4. The credential **never** appears in a prompt, journal, trace, audit line, tool result, error or log.
5. Existing scripts and hosts are unchanged. `invoker.*` is opt-in.

## Non-goals

- Making the whole `HarnessExecutor` re-entrant (see [Concurrent runs](#concurrent-runs-on-one-executor)). This change makes the
  credential path safe for it and removes one obstacle; it does not finish the job.
- Doing the OAuth flow. The host owns login, storage of the refresh token and the refresh call.
- Per-user LLM provider keys (`provider … api_key: invoker.x` is refused; providers are per-executor clients).
- `scheduled` or trigger-started runs acting as a user. They have no invoker; see [Runs without a invoker](#runs-without-a-invoker).

## Script syntax

```loom
tool Refunds {
  use: openapi
  spec: "specs/refunds.json"
  auth_header: "Authorization"
  auth_value: invoker.token          // the whole header value, e.g. "Bearer eyJ…"
}
```

`invoker.NAME` is a third kind of credential reference beside `env.NAME` and `secret.NAME`. `NAME` follows the same rule as a
secret name (`SecretNames.isValid`: a letter, then letters, digits, `_` or `-`). It is allowed only on options a tool kind
declares **late-bound** (first version: `auth_value` of `openapi`). Anywhere else it is a load error that names where it is allowed.

`auth_value` is the whole header value, so the host includes `Bearer ` in what it returns, as it must today with `env.`.

## Host API

In `ai-agent4j` (package `io.github.llm4j.secret`, beside `SecretRef`), so tools in any module can use it:

```java
/** What a run knows about the person it runs for. Implemented by the host. */
public interface InvokerCredentials {

    /**
     * The current value of a credential, e.g. get("token") → "Bearer eyJ…". Called for EVERY request that needs it,
     * from any thread, possibly in parallel: be fast when the token is valid, and thread-safe. Refresh here:
     * if the access token is expired or close to it, use the refresh token and return the new one.
     * Return null if there is no such credential; throw {@link SecretException} if it cannot be obtained.
     */
    String get(String name) throws SecretException;

    /**
     * The server rejected the value last returned for {@code name} (HTTP 401). Drop any cached copy so the next
     * {@link #get} fetches a new one. Default: nothing to drop.
     */
    default void invalidate(String name) {}

    /** True if this object can ever supply {@code name}; lets a run fail at its start, not mid-run. Default: yes. */
    default boolean provides(String name) { return true; }

    static InvokerCredentials none();                                   // provides nothing
    static InvokerCredentials of(Map<String, String> fixed);            // constant values (tests, CLI)
    static InvokerCredentials of(String name, Supplier<String> value);  // one credential from a supplier
}
```

A typical host implementation:

```java
class UserTokens implements InvokerCredentials {
    private final TokenStore store; private final String userId;       // holds access + refresh token
    public String get(String name) {
        if (!"token".equals(name)) return null;
        return "Bearer " + store.accessTokenFor(userId);               // refreshes with the refresh token when expired
    }
    public void invalidate(String name) { store.dropAccessToken(userId); }
    public boolean provides(String name) { return "token".equals(name); }
}
exec.executeWorkflow("Main", inputs, new UserTokens(tokenStore, user.id()));
```

`HarnessExecutor` gets one new overload; the old one delegates with `InvokerCredentials.none()`:

```java
void executeWorkflow(String workflow, Map<String,String> initialContext, InvokerCredentials invoker);
```

`LoomEngine` gets the same overload as a `default` method that ignores the credentials (other engines keep compiling).

## How it flows

```
web request ──► executeWorkflow(wf, ctx, invoker)
                  │  invokerScope.set(invoker)            (top-level run only)
                  ▼
        statements ──► delegate ──► runWithTimeout / forkWith / broadcast / callWithTimeout
                                         │  RunScope.capture() on the submitting thread,
                                         │  install()/restore() on the pool thread
                                         ▼
                              ReActAgent.run  (tools run on the same thread)
                                         ▼
                      OpenAPITool.execute ──► credentialSupplier.get()      ← per request
                                                  │ = invokerScope.get().get("token")
                                                  ▼
                                  header set; value added to the scrub list
                                  401 → invalidate("token") → get() again → retry once
```

### 1. The run scope (the structural change)

The executor already keeps per-thread state in four `ThreadLocal`s (`step`, `locals`, `scopes`, plus `attempt`) and copies
`step`, `locals` and `scopes` by hand at each of **four** places that move work to another thread:
`forkWith`, the broadcast `supplyAsync`, `callWithTimeout`, `runWithTimeout`. Each is a copy of the same capture/set/finally-remove block.

Add a small package-private `RunScope`:

```java
final class RunScope {                       // an immutable snapshot of what a worker thread must inherit
    final String step; final Map<String,Object> locals; final List<Budget> budgets; final InvokerCredentials invoker;
    Restore install();                       // sets the thread-locals on the current thread; Restore.close() removes them
}
RunScope capture();                          // read from the current thread
```

The four sites become `RunScope s = capture(); … () -> { try (var r = s.install()) { work } }`. This (a) carries the new
`invoker` everywhere without four more copies, (b) fixes the class of bug "a new thread-local was added and one site forgot it",
and (c) is the first piece of any later re-entrant executor, which would hang the rest of the per-run state off the same object.

`invokerScope` is a `ThreadLocal<InvokerCredentials>` on the executor. It is set by the top-level `executeWorkflow` and cleared in
its `finally`. Nested `executeWorkflow` (a `call` of another workflow, where `parent` is non-empty) inherits it.

Why a thread-local and not a field: two runs on two threads of one executor each see their own value. A field would be last-writer-wins.

### 2. Late-bound options in tools

`ToolDef.OptionValue.Source` gains `CALLER`. Because many places write `v.isReference() ? env.apply(v.lookupKey()) : v.value()`,
a silent fall-through would send the *name* ("token") as a credential. So:

- `isReference()` stays true for ENV and SECRET only; new `fromInvoker()` and `isCredential()` (any non-literal).
- `lookupKey()` **throws** `IllegalStateException` for CALLER.
- Every existing call site is made explicit (table below). A grep-based test fails the build if a new `lookupKey()` call appears that does not handle CALLER.

| Call site | Behaviour for `invoker.*` |
|---|---|
| `LoomParser.optionValue` | parses `invoker.NAME`, validates NAME |
| `ToolFactory.problems`, `resolve` | not resolved; passed on as a late-bound supplier; allowed only for options the kind lists in `lateBound()` |
| `ToolFactory.checkPrefixed` (`header.<Name>`) | `invoker.*` refused in v1 (message says so) |
| `HarnessExecutor.checkProviders`, `option`, `clientFor` | refused: "invoker credentials are for tools" |
| `HarnessExecutor.credentialValue`, `Decider.secrets` | no value to scrub at load time; the tool scrubs per request |
| `AgentIdentity.optionValue` | `invoker:NAME` (changing `env.X` to `invoker.x` is a changed agent, which is right) |
| `SecurityAudit`, `Capabilities.opt` | new finding "acts as the invoker"; see §6 |
| `LanguageTools` defaults, `AgentVoice` | unchanged (never CALLER) |

`ToolKind` gains:

```java
default Set<String> lateBound() { return Set.of(); }          // options that may be invoker.NAME
default Tool create(String name, Map<String,String> options, Map<String, Supplier<String>> lateBound,
                    Path baseDir, EffectContext context) throws Exception { return create(name, options, baseDir, context); }
```

`ToolFactory.create` gets an overload taking the `InvokerBinding` (a `Function<String, Supplier<String>>`): for each late-bound option it
passes `() -> binding.get(name)`. The executor's binding is:

```java
name -> () -> {
    InvokerCredentials c = invokerScope.get();
    if (c == null) throw new SecretException("this run has no invoker credentials (invoker." + name + ")");
    String v = c.get(name);
    if (v == null) throw new SecretException("the invoker has no credential '" + name + "'");
    return v;
}
```

The `openapi` kind lists `auth_value` as late-bound. `OpenAPITool.Builder` gets:

```java
headerAuth(String header, CredentialSource source)      // CredentialSource { String get() throws SecretException; default void rejected() {} }
apiKeyAuth(String param,  CredentialSource source)
```

`rejected()` is wired to `InvokerCredentials.invalidate(name)`. These sit beside the existing `SecretRef` variants, which stay.

### 3. In the tool, per request

`OpenAPITool.executeRequest` already resolves `authHeaderSecrets` / `authQuerySecrets` for each request, adds the value (and the part after
`Bearer `) to the `credentials` list used by `scrub()`, and returns `"Error: credential unavailable: …"` on `SecretException`. The new sources
use the same path, so there is one place that handles per-request credentials.

Added behaviour:

- **401 retry:** if the response is 401 and the credential came from a `CredentialSource`, call `rejected()`, fetch the value again and retry the
  request **once**. A second 401 is returned as the normal error. (A rejected request had no effect, so a retry of POST/PUT/DELETE is safe.)
- **https only:** a invoker credential is sent only to an `https://` server, or to `localhost`. The server is `spec.servers[0]`, which the agent
  cannot change (it picks the path, not the host). This reuses the rule in `HarnessExecutor.checkProviderEndpoint`.
- **Not cached:** the tool keeps no copy of the value between requests.

### 4. Validation

- At load: `invoker.NAME` on an option that is not late-bound is a load error; `weave check` reports it with a suggestion.
- At run start: for every distinct `invoker.NAME` in the script, `invoker.provides(NAME)` must be true, otherwise the run fails **before the first
  step** with `MissingInvokerCredentialException` naming the missing names (never values). A supplier that returns true and then fails later
  fails the tool call, not the run (the agent gets the "credential unavailable" observation).
- `weave check` / `weave audit` (no invoker): the script is valid; the check prints "uses invoker credentials: token (supplied by the host at run time)".

### 5. Runs without a invoker

Triggers, schedules (`TriggerRunner`) and `weave run` have no `InvokerCredentials`. A script using `invoker.*` run that way fails at run start with
`MissingInvokerCredentialException`, unless a run is started with `--invoker-env NAME=ENVVAR` (the CLI builds `InvokerCredentials.of(...)` from environment
variables), which exists so a developer can run such a script from a terminal. Nothing else grants a scheduled run a user's identity; a
host that wants a background job to act as a user starts it itself with its own `InvokerCredentials` (for example from a stored refresh token).

### 6. Safety

| Concern | Rule | Where |
|---|---|---|
| Token in a prompt or in the model's view | Never. The agent sees the tool description (endpoints) and results only. | `OpenAPITool.getDescription` unchanged |
| Token in a result, error, log | Scrubbed per request: the value, and the part after `Bearer ` | `OpenAPITool.scrub` |
| Token in the journal, trace, audit | Never stored; a resumed run is given a **fresh** `InvokerCredentials` by the host | tests CC-V5.* sweep every file the run wrote |
| Token to the wrong host | https only; host is the spec's server, fixed by the script author | §3 |
| Token used by an agent that should not have it | Same as any tool: only agents listing the tool; `approve:` still applies | existing |
| Token across threads/runs | thread-bound scope, re-installed at every fork, removed in `finally` | §1 |
| Script author uses a user's identity unnoticed | `weave audit` finding "acts as the invoker"; includes methods that write | `SecurityAudit` |
| A pool thread keeps the previous run's credential | `install()` returns a `Restore` that removes (not restores-to-null-and-forgets); test CC-V3.4 | `RunScope` |

`weave audit` finding (new id, severity MEDIUM, LOW if the tool is GET-only): *"Tool Refunds acts as the person running the workflow
(auth_value: invoker.token) and can POST/PUT/DELETE. Put `approve: Refunds` on the agents that use it."*

## Concurrent runs on one executor

The question raised in review: should several users' runs share one `HarnessExecutor`? **This change makes the credential path correct under that
use, and no more.** The rest of the executor is per-run state held in fields, so two concurrent top-level runs would corrupt each other:

| State | Today | Needed for re-entrancy |
|---|---|---|
| Variables (`context`), `locals` | one `DefaultVariableContext` per executor | per run |
| `journal`, `runId`, `runLocator`, `generations`, `resumes`, `rewindsUsed` | executor fields | per run |
| Budgets (`runBudget`, `spendLines`, `budgetExhausted`) | executor fields | per run |
| `activeAgents` (`ReActAgent`) | built once, shared | per run if an agent keeps conversation state (not verified; to check) |
| `sessions`, `servedModels`, `createdTools` | executor maps | `createdTools` can stay shared (tools hold no run state after this change); the others per run |
| `step`, `locals`, `scopes`, `attempt`, **`invokerScope`** | thread-local, already copied at forks | already per thread |

What this spec delivers toward it: `RunScope` as the single place per-thread run state is captured and re-installed, and tools whose
only run-dependent input arrives through that scope (so `createdTools` can be shared across runs). What it does not: a `Run` object that owns
the fields above. That is a separate spec (`loom-reentrant-executor`); this one is written so it can adopt `RunScope` unchanged.

Until then: **one top-level run at a time per executor; use one executor per request** (cheap once `initialize()` is cheap; see Decisions taken in review, item 1).

## Decisions

- **`invoker.NAME`, not a magic variable or a Java-only hook.** The script states, in the open, which tool acts as the person; `weave audit` can list it.
- **A supplier object, not a token string.** Expiry and refresh belong to the host, which owns the refresh token; Loom only asks again.
- **Asked on every request, not once per run.** A run can outlive an access token (suspend, approval wait, retries).
- **Thread-local scope, re-installed at forks, not a field.** Correct for concurrent runs and for parallel branches.
- **`lookupKey()` throws for CALLER.** A forgotten call site fails loudly in tests instead of sending the name as a credential.
- **`openapi` first; `http` next.** `HttpKind` builds from strings through `Options`/`HttpTool.Config`; supporting a supplier there needs
  `HttpSupport` and `Redactor` changes. Listed as an optional task, not mixed into the first change.
- **No retry beyond one 401.** Anything more is the host's refresh policy.

## Decisions taken in review

1. **Concurrent top-level runs on one executor throw.** A second top-level `executeWorkflow` that starts while another is still running on the same
   executor fails with `IllegalStateException` pointing at this spec ("use one executor per request, or wait for the reentrant executor").
   Silent corruption of shared run state is worse than a clear error. Nested workflows (`call`) and parallel branches of one run are not top-level and are unaffected.
2. **The name is `invoker`** (`invoker.token`, `InvokerCredentials`): neutral about whether the invoker is a person or a service.
3. **`get` does not take the request.** The name selects among several credentials (`invoker.token`, `invoker.admin_token`) and the host is fixed by the
   spec, so per-request minting adds complexity without a use. Revisit if a real need appears.

## Open questions

1. **Does `ReActAgent` keep conversation state across `run()` calls?** Needed for the re-entrancy table; the credential path does not depend on it.
