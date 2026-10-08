# Budgets and Rate Limits

Two things decide whether an agent can keep working: **what you are willing to spend**, and **what the
provider will let you do right now**. ai-agent4j handles both at the one place every token passes
through: `LLMClient.chat()`.

- **Budgets** cap tokens, calls or money for an agent, a task or a whole application. A call that can't be
  paid for is refused before it reaches the model, and never retried.
- **Rate limits** (HTTP 429) are read, not guessed. The library works out *when* the limit lifts, waits
  out short limits by itself, and hands long ones to you as an exact instant, so you can pause the work and
  pick it up again.

Both are opt-in. An agent without a budget is not metered, and nothing about its behaviour changes.

---

## Contents

1. [Quick start](#1-quick-start)
2. [Budgets](#2-budgets)
3. [Money: price tables](#3-money-price-tables)
4. [Budgets that refill (windows)](#4-budgets-that-refill-windows)
5. [What an agent does when it runs out](#5-what-an-agent-does-when-it-runs-out)
6. [Metering any client: `BudgetedLLMClient`](#6-metering-any-client-budgetedllmclient)
7. [Several budgets at once: `BudgetSet`](#7-several-budgets-at-once-budgetset)
8. [Events and reporting](#8-events-and-reporting)
9. [Rate limits](#9-rate-limits)
10. [Tuning the HTTP layer](#10-tuning-the-http-layer)
11. [Recipes](#11-recipes)
12. [Guarantees and edge cases](#12-guarantees-and-edge-cases)
13. [API reference](#13-api-reference)

---

## 1. Quick start

```java
Budget budget = Budget.builder()
        .name("research")
        .tokens(50_000)          // at most 50k tokens (prompt + completion)
        .calls(40)               // and 40 model calls
        .warnAt(0.8)             // tell me at 80%
        .build();

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new DuckDuckGoSearchTool())
        .budget(budget)
        .maxTokensPerCall(1500)  // no single answer may be longer than this
        .build();

AgentResult result = agent.run("Summarise this week's papers on agent memory");

if (result.budgetExhausted()) {
    // It ran out part-way: this is its best answer so far, clearly marked.
    System.out.println("Partial: " + result.getFinalAnswer());
    System.out.println("Because: " + result.getBudgetExceeded().getMessage());
}
System.out.println(budget.spent());      // tokens, calls, cost, and whether any usage was estimated
System.out.println(budget.remaining());  // what is left of each limited dimension
```

And for rate limits, nothing to set up: a 429 that lifts within 30 seconds is waited out inside the call.
A longer one reaches you as `RateLimited`:

```java
try {
    agent.run("…");
} catch (RateLimited limited) {
    System.out.println(limited.info().describe());   // "google daily quota (GenerateRequestsPerDay…)"
    System.out.println("Try again at " + limited.resetAt());
}
```

---

## 2. Budgets

A `Budget` is an allowance plus running totals. It is thread-safe, and any number of agents, threads or
requests can share one.

| Builder method | Meaning |
|---|---|
| `name(String)` | Shown in messages, events and reports. Default `"budget"`. |
| `tokens(long)` | Prompt + completion tokens. |
| `calls(long)` | Model calls. |
| `cost(String \| BigDecimal)` | Money, e.g. `"0.50"` or `"$0.50"`. Needs a [price table](#3-money-price-tables). |
| `warnAt(double)` | Fraction in (0, 1] at which one `WARNING` event fires. Default `0.8`. |
| `window(Window)` | `MINUTE`, `HOUR` or `DAY`: the limits apply per window (see [§4](#4-budgets-that-refill-windows)). |
| `clock(Clock)` | Time source for windows (tests use a fixed one). Its zone aligns the windows. |
| `listener(BudgetListener)` | Receives `WARNING` and `EXHAUSTED` events. |

Unset dimensions are unlimited. `Budget.unlimited("run")` only counts, which is handy for reports.

**How a call is charged.**

1. **Before the call** the request is estimated (prompt tokens, plus the output it may produce) and that
   amount is *reserved*. If it can't be reserved, `BudgetExceeded` is thrown and **the model is not
   called**.
2. **The answer is capped.** The request's `maxTokens` is lowered to what the budget can still afford, so
   one long answer can't blow through the limit. If less than 64 output tokens (or less than you asked
   for, when that is smaller) can be afforded, the call is refused instead of producing a useless stub.
3. **After the call** the reservation is replaced by the provider's reported usage. If the provider reports
   none, the usage is estimated and marked `estimated = true` everywhere it shows up.
4. **A failed call** (timeout, 5xx) is charged its prompt, estimated, because providers may bill it. A
   **429 rate-limit refusal** is charged as one call and **no tokens**.

**Reading a budget.**

```java
Spent s = budget.spent();          // promptTokens, completionTokens, tokens(), calls, cost, estimated, overdraw
Remaining r = budget.remaining();  // OptionalLong tokens/calls, Optional<BigDecimal> cost (empty = unlimited)
budget.exhausted();                // true once any limited dimension has nothing left
budget.refused();                  // true once it has refused a call
budget.limits();                   // Limits(tokens, calls, cost)
```

**Changing a budget.**

```java
budget.restore(previouslySpent);           // add spend recorded elsewhere, e.g. a resumed job's history
budget.restore(previouslySpent, whenSpent); // windowed budgets ignore spend from earlier windows
budget.raise(new Limits(10_000L, null, null)); // allow 10k more tokens (e.g. a person approved a top-up)
```

`raise` adds to the limits that exist (an unset dimension stays unlimited) and lets the budget warn and
refuse again.

**Estimation.** The default `CharsPerTokenEstimator` counts about 4 characters per token plus 10% for
prompts and answers. It is only used *before* a call and when a provider reports no usage; reported usage
always wins. Supply your own `TokenEstimator` (with `.tokenEstimator(…)`) if you have a real tokenizer.

---

## 3. Money: price tables

Prices change often, so **none ship with the library**. Write your own file, in your own currency, per
million tokens:

```properties
# prices.properties — model = input / output (per million tokens)
gemini/gemini-2.5-flash = 0.30 / 2.50
gemini/gemini-2.5-pro   = 1.25 / 10.00
anthropic/claude-sonnet = 3.00 / 15.00
```

```java
PriceTable prices = PriceTable.load(Path.of("prices.properties"));
// or: PriceTable.of(Map.of("gemini/gemini-2.5-flash", new PriceTable.Price(new BigDecimal("0.30"), new BigDecimal("2.50"))))

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .budget(Budget.builder().cost("0.25").build())   // 25 cents for this task
        .priceTable(prices)
        .budgetModel("gemini/gemini-2.5-flash")          // which price applies when requests don't name a model
        .build();
```

- Input and output are priced separately, so an answer-heavy call costs what it really costs.
- Models named `ollama/*` run locally and cost nothing unless you list them.
- A cost budget with no price table, or with a model missing from it, fails on the first call with a
  message naming the model: never silently free.
- Without a cost limit, a price table still fills in `AgentResult.getUsage().getCost()` and each
  response's `llm4j.cost` metadata.

---

## 4. Budgets that refill (windows)

Background agents usually want an **allowance**, not a lifetime cap: "100k tokens a day, every day".

```java
Budget daily = Budget.builder()
        .name("daily")
        .tokens(100_000)
        .window(Window.DAY)                    // or MINUTE, HOUR
        .clock(Clock.system(ZoneId.of("Asia/Kolkata")))  // days start at local midnight
        .build();
```

- **Windows are aligned** to the clock's zone: whole minutes, hours, or days from local midnight.
- **When a window rolls over**, spend, warnings and refusals start again from zero. `spent()` is the
  current window; `lifetimeSpent()` is everything.
- **Calls in flight** across a boundary are settled exactly: their charge lands in the new window, and the
  reservation from the old one is released.
- **Refusals say when the budget refills**: `BudgetExceeded.resetAt()` returns the window's end
  (empty for a lifetime budget), and the message ends with `refills at …`.
- `budget.windowEnd()` tells you the same thing without waiting to be refused.

---

## 5. What an agent does when it runs out

`ReActAgent.Builder.onBudgetExhausted(BudgetPolicy)`:

| Policy | Behaviour |
|---|---|
| `RETURN_PARTIAL` (default) | The run stops. `getFinalAnswer()` is the last thought (or last observation), `budgetExhausted()` is true, `getBudgetExceeded()` says which budget and dimension, and the last step's outcome is `BUDGET_EXHAUSTED`. |
| `FAIL` | `BudgetExceeded` propagates to your code. |
| `SUSPEND` | If the refusing budget refills at a known time (a [window](#4-budgets-that-refill-windows)), throw `RateLimited` with reason `BUDGET_WINDOW` and that time, so a caller can pause and resume. Otherwise behave as `RETURN_PARTIAL`. |

`BudgetExceeded` and `RateLimited` both extend `AgentInterrupt`. The ReAct loop never turns them into a
tool error or retries them, because retrying would only spend more or hit the same wall.

---

## 6. Metering any client: `BudgetedLLMClient`

Agents wrap their client for you. To meter anything else, such as a summariser, a classifier or your own
loop, wrap it yourself:

```java
BudgetedLLMClient metered = BudgetedLLMClient.builder(client)
        .budget(budget)                  // or .budgets(() -> BudgetSet.of(run, user, request))
        .prices(prices)
        .model("gemini/gemini-2.5-flash")
        .perCallCap(800)                 // cap every answer
        .estimator(CharsPerTokenEstimator.INSTANCE)
        .build();

metered.addChargeListener((model, charge) -> log.info("{} cost {}", model, charge.cost()));
LLMResponse r = metered.chat(request);
r.getMetadata().get(BudgetedLLMClient.ESTIMATED);  // true when usage was estimated
r.getMetadata().get(BudgetedLLMClient.COST);       // BigDecimal, when priced
```

Streaming is metered too: the stream is settled when it ends or is closed.

---

## 7. Several budgets at once: `BudgetSet`

Real applications have layers: the whole app for today, this user, this request. A `BudgetSet` applies
them together:

```java
Budget app  = Budget.builder().name("app").tokens(5_000_000).window(Window.DAY).build();
Budget user = Budget.builder().name("user:42").tokens(200_000).window(Window.DAY).build();

BudgetedLLMClient client = BudgetedLLMClient.builder(base)
        .budgets(() -> BudgetSet.of(app, user, Budget.builder().name("request").tokens(8_000).build()))
        .build();
```

- A call must fit **every** member and is charged to **every** member.
- Reservation is **all-or-nothing**: if any member refuses, nothing is reserved anywhere.
- Members are locked in one global order, so any number of threads using overlapping sets can't deadlock.
- The supplier is read on **every call**, so the set can change per request or per thread.

---

## 8. Events and reporting

```java
Budget b = Budget.builder().tokens(100_000).warnAt(0.75)
        .listener(e -> alerts.send(e.kind() + " " + e.budget() + ": " + e.spent().tokens() + " / " + e.limits().tokens()))
        .build();
```

- `WARNING` fires once when any limited dimension crosses `warnAt` (once per window for windowed budgets).
- `EXHAUSTED` fires once, on the first refusal.
- Agents also forward both to `AgentEventListener.onBudget(BudgetEvent)`, next to your other agent events.
- A listener that throws never breaks metering.

`AgentResult.getUsage()` reports `getLlmCalls()`, `getPromptTokens()`, `getCompletionTokens()`,
`getTotalTokens()`, `getCost()` (when priced) and `isEstimated()`.

---

## 9. Rate limits

### What the library reads

When a provider answers **429**, the HTTP layer builds a `RateLimitInfo`, trying these in order:

| Source | Example | Gives |
|---|---|---|
| Anthropic headers | `anthropic-ratelimit-tokens-remaining: 0`, `anthropic-ratelimit-tokens-reset: 2026-09-27T10:00:45Z` | the exhausted dimension's reset, limit, remaining |
| OpenAI-style headers | `x-ratelimit-remaining-tokens: 0`, `x-ratelimit-reset-tokens: 6m0s` | same, durations like `1h2m3.5s`, `120ms` |
| Google's error body | `google.rpc.RetryInfo.retryDelay: "34s"`, `google.rpc.QuotaFailure…quotaId` | the delay, and whether it is a **daily quota** |
| `Retry-After` | `120` or `Sun, 27 Sep 2026 10:05:00 GMT` | the reset |
| Nothing usable | | an **estimate**: 60 s, doubling on each further 429 from that provider (up to 1 h), reset by a success |

**Daily quotas.** Gemini's free tier reports a per-day quota (`…PerDay…` in the `quotaId`) with a short
`retryDelay` that would only get you refused again. The library recognises it and sets the reset to the
**next midnight, Pacific time**, when Google resets daily quotas.

`RateLimitInfo` carries:

| Field | Meaning |
|---|---|
| `resetAt()` | An absolute `Instant`, meaningful hours later on another machine. |
| `scope()` | `REQUESTS`, `TOKENS`, `DAILY_QUOTA` or `UNKNOWN`. |
| `provider()` | `google`, `anthropic`, `openai`, `sarvam`, `ollama` or the host (`budget:<name>` for budget windows). |
| `quotaId()`, `limit()`, `remaining()` | When the provider says. |
| `estimated()` | True when the reset time is a guess. |
| `describe()` | e.g. `google daily quota (GenerateRequestsPerDayPerProjectPerModel-FreeTier)`. |

### What happens next

| Reset is… | Behaviour |
|---|---|
| within 30 s (and retries remain) | The call **waits until the reset** (plus up to 10% jitter) and retries. Your code sees nothing but a slower call. |
| further away | `RateLimitException` (an `LLMException` with status 429) is thrown **without retrying**. `info()` has the details; `getRetryAfterSeconds()` still works. |

Providers (Google, Ollama, Sarvam) pass `RateLimitException` through instead of wrapping it. In an agent,
it becomes **`RateLimited`**, an `AgentInterrupt` with `info()`, `resetAt()` and
`reason() == PROVIDER_LIMIT`. The agent does not return a half-finished answer, and your code (or a
harness like Loom) decides what to do: wait, reschedule, or fail.

**Routing.** `RoutingLLMClient` fails over to other tiers on a 429 as before. If *every* tier is
rate-limited, it throws a `RateLimitException` for the tier that frees up **soonest**.

---

## 10. Tuning the HTTP layer

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .addRetryableStatusCode(429).addRetryableStatusCode(503)
        .inlineWaitThreshold(Duration.ofSeconds(30))   // wait inline up to this; longer limits are raised
        .fallbackDelay(Duration.ofSeconds(60))         // assumed wait when a 429 says nothing
        .maxFallbackDelay(Duration.ofHours(1))         // cap for the doubling fallback
        .dailyResetZone(ZoneId.of("America/Los_Angeles")) // where "per day" quotas reset
        .clock(Clock.systemUTC())                      // for tests
        .sleeper(Sleeper.SYSTEM)                       // for tests: record waits instead of sleeping
        .build();

LLMConfig config = LLMConfig.builder().apiKey(System.getenv("GEMINI_API_KEY")).retryPolicy(policy).build();
```

Other retryable statuses (500, 502, 503, 504) keep their exponential backoff.

---

## 11. Recipes

**A nightly job that stops at a fixed cost.**

```java
Budget tonight = Budget.builder().name("nightly").cost("2.00").warnAt(0.9).build();
ReActAgent agent = ReActAgent.builder().llmClient(client).budget(tonight).priceTable(prices)
        .budgetModel("gemini/gemini-2.5-flash").onBudgetExhausted(BudgetPolicy.FAIL).build();
```

**A per-user daily allowance in a web app.**

```java
Budget forUser(String userId) {
    return allowances.computeIfAbsent(userId, id ->
            Budget.builder().name("user:" + id).tokens(50_000).window(Window.DAY).build());
}
// per request:
ReActAgent agent = baseAgent.toBuilder().budget(forUser(userId)).build();
```

**A background worker that sleeps through limits.**

```java
while (!queue.isEmpty()) {
    try {
        agent.run(queue.peek());
        queue.remove();
    } catch (RateLimited limited) {                 // provider limit or a budget window
        Duration wait = Duration.between(Instant.now(), limited.resetAt());
        log.info("Paused: {} — back at {}", limited.info().describe(), limited.resetAt());
        Thread.sleep(Math.max(0, wait.toMillis()));
    }
}
```

For workflows that should pause **without a thread**, survive restarts and resume on another machine,
use [Loom](../../loom/ai-agent4j-loom/BUDGETS_AND_SCHEDULING.md). It builds on exactly these pieces.

---

## 12. Guarantees and edge cases

- **No overspend under concurrency.** Reservations are made under the budget's lock, so concurrent calls
  can't jointly exceed a limit. The only overdraw possible is the gap between a prompt's estimate and its
  real size, reported in `spent().overdraw()`.
- **A provider that ignores `maxTokens`** can produce more than was reserved; the real usage is still
  charged, and the next call is refused.
- **Refusals are never retried**, by the agent or by the HTTP layer.
- **Short rate limits are waited out exactly**, not by fixed backoff; long ones are never retried blindly.
- **Parsers never throw.** A malformed header or body falls through to the next source, and in the end to
  the estimate.
- **Unbudgeted agents are untouched.** No wrapper, no estimation, no behaviour change.

---

## 13. API reference

| Type (package `io.github.llm4j.budget`) | Purpose |
|---|---|
| `Budget`, `Budget.Builder` | An allowance with running totals; `spent`, `remaining`, `lifetimeSpent`, `restore`, `raise`, `windowEnd` |
| `Window` | `MINUTE`, `HOUR`, `DAY` |
| `BudgetSet`, `BudgetSet.Lease` | Several budgets per call; all-or-nothing reservation |
| `BudgetedLLMClient` | Meters any `LLMClient`; `ChargeListener`; metadata keys `ESTIMATED`, `COST` |
| `BudgetExceeded` | Refusal: `budget()`, `dimension()`, `spent()`, `limits()`, `resetAt()` |
| `BudgetPolicy` | `RETURN_PARTIAL`, `FAIL`, `SUSPEND` |
| `BudgetEvent`, `BudgetListener` | `WARNING` / `EXHAUSTED` notifications |
| `PriceTable`, `PriceTable.Price` | Your prices per million input/output tokens |
| `TokenEstimator`, `CharsPerTokenEstimator` | Estimates before a call and when usage isn't reported |
| `Spent`, `Remaining`, `Limits`, `Charge`, `Dimension` | Values |

| Type (package `io.github.llm4j.ratelimit`) | Purpose |
|---|---|
| `RateLimitInfo` | Reset instant, scope, provider, quota id, limit/remaining, estimated |
| `RateLimited` | The agent-level interrupt: `info()`, `resetAt()`, `reason()` (`PROVIDER_LIMIT`, `BUDGET_WINDOW`) |
| `RateLimitParser`, `RateLimitParsers` | The parsers and the chain with its fallback |
| `AnthropicHeaders`, `OpenAiHeaders`, `GoogleRpcBody`, `RetryAfterParser`, `Durations` | Individual parsers |
| `Sleeper` | Injectable waiting |

`io.github.llm4j.exception.RateLimitException` is raised by the HTTP layer: `info()`,
`getRetryAfterSeconds()`, `infoOrEstimate(now, fallback)`.
