# Web-search grounding: one request option, typed citations

Some models can search the web inside a call and hand back the sources they used. llm4j makes that **one option on the request**, and the sources **typed fields on the response**, so the code that asks and the code that checks the answer does not change with the vendor.

> **Status.** This page describes the contract (`Grounding`, `Citation`, `SearchUsage`, the central check, the `searches` budget, telemetry). Which vendors implement it natively is listed in [Vendors](#vendors); a provider that does not is refused before any network call, never silently ignored.

## Ask

```java
LLMRequest request =
        LLMRequest.builder()
                .addUserMessage("What is the daily sodium limit?")
                .grounding(Grounding.webSearch().maxSearches(2).allowDomains("who.int", "nih.gov"))
                .build();
LLMResponse answer = client.chat(request);
```

`Grounding.webSearch()` builds the option. Every setting is optional:

| Setting | Meaning |
|---|---|
| `maxSearches(n)` | The most searches the model may run for this call (`n >= 1`). |
| `allowDomains(...)` / `blockDomains(...)` | Search only these sites, or never these. Mutually exclusive. Domains are normalised (`https://WWW.Who.int/x` becomes `who.int`). |
| `recency(Recency)` | Only sources from the last day, week, month or year. |
| `country("IN")` | Bias results to a country (ISO code). |
| `lenient()` | Drop settings the provider cannot honour instead of refusing (see below). |
| `fallback(Tool)` | If the provider cannot search, run this client-side search tool instead (see below). |

## Read the answer

```java
for (Citation c : answer.getCitations()) {
    System.out.println("[" + c.index() + "] " + c.title() + " " + c.url());
}
Integer searches = answer.getSearches().count(); // null when the vendor did not say
```

* `getCitations()` is a list of `Citation(index, url, title, snippet, spans)`. `index` counts from 1 and is stable for the response. `spans` are character offsets into `getContent()` (Java `String` indices), checked to be in range; a source with no span was used but not quoted at a position.
* `getSearches()` is a `SearchUsage(count, queries, estimatedCostUsd)`. `count` is **what the vendor reported**; when the vendor reports none it is `null` (unknown), never a guess.
* The metadata key `grounding.citation_basis` says whether the sources were **cited** (the model linked them in its answer) or only **consulted** (the vendor lists what it read, as Perplexity does).

Streaming and non-streaming agree: the final chunk of a stream carries the same citations and search usage as the whole response.

## Refusals are early and name both sides

```java
try {
    client.chat(LLMRequest.builder().addUserMessage("News?").grounding(Grounding.webSearch()).build());
} catch (InvalidRequestException e) {
    System.out.println(e.getMessage()); // names the provider: it does not support grounding
}
```

The check runs once, in `DefaultLLMClient`, before any request is built. It names the provider and the setting, for `chat` and `chatStream` alike. A provider declares what it can do with `groundingSupport()` (`NONE` or `NATIVE`) and `groundingOptions()`; the default is `NONE`.

`lenient()` trades the refusal for a note: the unsupported settings are removed from the request, and the response lists them in the metadata key `grounding.ignored`.

```java
LLMResponse r =
        client.chat(
                LLMRequest.builder()
                        .addUserMessage("What changed this week?")
                        .grounding(Grounding.webSearch().recency(Recency.WEEK).maxSearches(2).lenient())
                        .build());
Object dropped = r.getMetadata().get("grounding.ignored"); // [maxSearches]
```

`lenient()` never makes an unsupported *provider* work; that still refuses.

## No native search? Give it a tool

```java
LLMResponse r =
        client.chat(
                LLMRequest.builder()
                        .addUserMessage("What is the daily sodium limit?")
                        .grounding(Grounding.webSearch().fallback(webSearch))
                        .build());
List<Citation> sources = r.getCitations(); // the URLs the tool returned
```

If the provider cannot search but can call tools, `fallback(tool)` offers the tool to the model, runs it (at most `maxSearches` times, default 3) and returns the same `Citation` and `SearchUsage` shapes, built from the URLs the tool returned. Native grounding always wins: a provider that can search never calls the fallback.

## Count and cap searches

Searches are a budget dimension next to tokens, calls and cost:

```java
Budget budget = Budget.builder().name("run").searches(1).build();
LLMClient metered = BudgetedLLMClient.builder(model).budget(budget).build();
LLMRequest ask = LLMRequest.builder().addUserMessage("News?").grounding(Grounding.webSearch()).build();
metered.chat(ask); // runs one search
try {
    metered.chat(ask); // refused before it is made: no search left
} catch (BudgetExceeded e) {
    System.out.println(e.dimension()); // SEARCHES
}
```

A grounded call is refused (`BudgetExceeded`, dimension `SEARCHES`) before it is made when a budget has no search left; a plain call in the same budget still runs. The call reserves `maxSearches` (one if you gave none) and is settled with the **reported** count; if the vendor reports none, the reserved amount is charged and the spend is marked estimated. A vendor that ignores the cap is counted truthfully and the overrun shows in `Spent.searchOverdraw()`.

Searches are priced per thousand in the price table, as an optional third number:

```properties
# model = input / output [/ searches per 1000]   (your own prices; none ship with the library)
gemini/gemini-3.7-flash = 0.75 / 3.75 / 14.00
```

When the vendor reports what the searches cost, that figure is used instead.

## Telemetry

Each model-call event carries two numbers and nothing else: `searches()` (an `Integer`, null when none were run or the count is unknown) and `citations()`. `RunStats` adds them per model (`ModelStats.searches()`, `citations()`, `RunStats.totalSearches()`). Queries, addresses and text never enter an event.

## Vendors

| Provider | Native grounding | Status |
|---|---|---|
| Perplexity (Agent API), OpenAI (Responses), xAI | wired in the next step of this release | coming |
| Google (Gemini), Anthropic | wired as documentation-derived previews | coming |
| Kimi, Qwen, Ollama, Sarvam, Jev | not offered | refused; use `fallback(tool)` |

This table changes only when a vendor's grounding passes its tests against recorded responses.
