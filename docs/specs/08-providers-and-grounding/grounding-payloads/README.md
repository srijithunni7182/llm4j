# Grounding payloads (sub-phase 8d)

Raw grounded responses from the vendors' own APIs, captured on **2026-10-22** by [`capture.py`](capture.py) (a throwaway script, kept as evidence), scrubbed of keys and
ids. They are why [DESIGN §4.3](../DESIGN.md) says what it says: the mapping was built from these, not from memory. One question that needs current information ("the Reserve
Bank of India's current policy repo rate, and when it was last changed") was asked with each vendor's web search on, whole and streamed, plus a variant with the options we plan to map.

| File | Vendor / model | What it shows |
|---|---|---|
| `perplexity-plain.json`, `perplexity-plain-stream.sse` | Perplexity Agent API, `openai/gpt-6-luna` | `search_results` output item (id, url, title, snippet, date) **before** the message; the message's `annotations` is `[]`; `usage.tool_calls_details.search_web` = `{invocation, cost_usd}` |
| `perplexity-options.json` | the same with `search_domain_filter: [rbi.org.in]`, `search_recency_filter: year`, `user_location.country: IN` | filters honoured (all results from rbi.org.in); `status: incomplete` because the 500-token cap was spent |
| `perplexity-cap.json` | the same with `max_steps: 2` | one invocation either way: `max_steps` is a loop bound, not a cap on searches |
| `openai-plain.json`, `openai-plain-stream.sse` | OpenAI Responses, `gpt-6-luna`, `include: [web_search_call.action.sources]` | `web_search_call` items (`action.query`, `action.queries`, `action.sources` = bare URLs); `url_citation` annotations on the message; **no search count in `usage`** |
| `openai-options.json` | the same with `filters.allowed_domains`, `user_location.country`, `max_tool_calls: 2` | domains honoured; 2 searches `completed`, a third item left in `status: searching` (the cap) |
| `xai-plain.json`, `xai-plain-stream.sse` | xAI Responses, `grok-4.3` | `web_search_call` items; `url_citation` annotations whose `title` is just `"1"`, `"2"`; `usage.server_side_tool_usage_details.web_search_calls` and `cost_in_usd_ticks`; text carries `[[N]](url)` markers |
| `xai-options.json` | the same with `filters.allowed_domains` | honoured |

Added in 8f: `*-block.json` for each of the three, a blocked domain (`economictimes.indiatimes.com`, which the first Perplexity answer had used): Perplexity as a `-` prefix in `search_domain_filter`, OpenAI as `filters.blocked_domains`, xAI as `filters.excluded_domains`. All three answered 200 and none returned the blocked domain (xAI listed `m.economictimes.com`, a different domain), so block is **supported** for all three. Estimated spend for the three calls US$0.022.

Not captured: **Anthropic** and **Gemini** (no key in this environment). Their rows in DESIGN §4.3 come from the vendors' reference pages (read 2026-10-22) and are **preview** until a key exists.

## What the payloads settled

1. **Where the sources are differs by vendor.** OpenAI and xAI: `url_citation` annotations on the answer text (what the model cites). Perplexity: the `annotations` array is empty; the sources are the `search_results` item (what it consulted). So a citation list means "what the vendor attributes the answer to", and the response says which kind it is (`grounding.citation_basis` = `cited` or `consulted`).
2. **A search is not a query.** Perplexity: one `invocation` ran two queries. OpenAI: a `web_search_call` item has a `queries` list. The count is what the vendor counts (an invocation, a call item), and the queries are listed separately.
3. **Counts come from different places.** Perplexity `usage.tool_calls_details.search_web.invocation` (with the real cost, `cost_usd`); xAI `usage.server_side_tool_usage_details.web_search_calls` (cost only as a total in ticks); OpenAI: **no usage field**, so the count is the number of completed `web_search_call` items (R8: what the vendor reports). An item still `searching` when the response ended is not counted.
4. **`maxSearches`.** OpenAI honours `max_tool_calls` (2 completed, the third not run). Perplexity has no cap (`max_steps` did not limit it); xAI's was not tested. Those two refuse `maxSearches` unless `lenient()`.
5. **Spans mean the link, not the claim.** OpenAI and xAI annotation ranges cover the markdown link the model wrote into the text (`([host](url))`, `[[1]](url)`), right after the claim. They are kept as `Span`s as given; indices are Unicode code points and are converted to Java string offsets; a range outside the answer is dropped.
6. **Titles.** OpenAI gives the page title; xAI gives only the citation number (dropped as no information); Perplexity gives title and snippet in `search_results`.
7. **Streaming parity is free.** In all three, the final `response.completed` event carries the whole response object, search items and annotations included; reading that one object in both modes makes the streamed and whole answers agree (R9).
8. **The answer text keeps the links the model wrote** (`[[1]](url)`, `([RBI](url))`). They are the vendor's text; `getContent()` is not rewritten.
9. **Cost.** Perplexity reports `cost_usd` per tool (`$0.0025` a search); xAI reports the total in `cost_in_usd_ticks` (10,000,000,000 per dollar); OpenAI reports none, so the cost of a search is the user's `PriceTable` entry.

Spend for this capture: ten calls, estimated by the script's cap at US$0.089 (an upper bound; Perplexity's own `usage.cost` shows about US$0.003 a call).
