# Web-search grounding: one request option, typed citations

Some models can search the web inside a call and hand back the sources they used. llm4j makes that **one option on the request**, and the sources **typed fields on the response**, so the code that asks and the code that checks the answer does not change with the vendor.

> **Status.** The contract (`Grounding`, `Citation`, `SearchUsage`, the central check, the `searches` budget, telemetry) and native grounding for Perplexity, OpenAI and xAI are supported (recorded from the real APIs); Google and Anthropic are preview; see [Vendors](#vendors). A provider that cannot ground is refused before any network call, never silently ignored. Grounding in the agent loop, in Loom scripts and in eval4j is *coming*.

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
| `allowDomains(...)` / `blockDomains(...)` | Search only these sites, or never these. Mutually exclusive. Domains are normalised: the scheme and a trailing slash are dropped and the name is lower-cased (`https://WHO.int/` becomes `who.int`); a path is kept, for vendors that accept one. |
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

```java
LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(config));
LLMResponse r =
        perplexity.chat(
                LLMRequest.builder()
                        .addUserMessage("What is the RBI's current policy repo rate?")
                        .grounding(Grounding.webSearch().recency(Recency.YEAR).country("IN"))
                        .build());
String basis = (String) r.getMetadata().get("grounding.citation_basis"); // consulted
Double cost = r.getSearches().estimatedCostUsd(); // 0.0025: Perplexity reports it
```

| Provider | How | Status | Settings it honours |
|---|---|---|---|
| Perplexity Agent API | `OpenAiResponsesProvider.perplexity(cfg)`; Loom `use: perplexity` | **supported**: recorded 2026-10-22 | `allowDomains`, `blockDomains`, `recency`, `country` (no `maxSearches`) |
| OpenAI (Responses API) | `OpenAiResponsesProvider.openai(cfg)`; Loom `use: openai  api: responses` | **supported**: recorded 2026-10-22 | `maxSearches`, `allowDomains`, `blockDomains`, `country` |
| xAI (Responses API) | `OpenAiResponsesProvider.xai(cfg)`; Loom `use: xai  api: responses` | **supported**: recorded 2026-10-22 | `allowDomains`, `blockDomains` (at most 5 each) |
| Google (Gemini) | `GoogleProvider` | **preview**: written from Google's reference, no key to record with | `recency` only |
| Anthropic (Claude) | `AnthropicProvider` | **preview**: one real non-streaming answer was recorded on 2026-10-22 and read correctly (text, spanned citations, search count); streaming and the other settings are still written from Anthropic's reference. Long turns that Claude pauses are continued by `chat` | `maxSearches`, `allowDomains`, `blockDomains`, `country` |
| Kimi, Qwen | none | left out: no key; Kimi's search function is deprecated | refused; use `fallback(tool)` |
| Ollama, Sarvam, Jev, chat-completions OpenAI and xAI | none | refused | use `fallback(tool)` or the Responses API |

"Supported" means the mapping is tested on responses recorded from the vendor's real API (the files are in `src/test/resources/providers`, each with its date and model). "Preview" means it is tested only on fixtures written from the vendor's documentation, so it may need a correction the first time it meets the real service.

What the recordings showed, and the code follows:

* **Cited or consulted.** OpenAI and xAI cite sources in the answer (`grounding.citation_basis` = `cited`, with spans); Perplexity lists what it consulted (`consulted`, no spans).
* **A search is what the vendor counts.** Perplexity counts an *invocation* that may run several queries; OpenAI reports no count, so the completed search items are counted; xAI and Anthropic report one in the usage.
* **Cost.** Only Perplexity reports what the searches cost (`SearchUsage.estimatedCostUsd()`); for the others put the price in your price table.
* **Titles.** xAI gives only the citation number as a title; it is dropped, not shown as a title.
* **The answer keeps the links the model wrote**; `getContent()` is not rewritten.

Spans are character offsets: OpenAI and xAI count Unicode code points and Google counts UTF-8 bytes; both are converted, and a range that does not fit the answer is dropped.

This table changes only when a vendor's grounding passes its tests against recorded responses.
