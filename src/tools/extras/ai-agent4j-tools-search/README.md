# ai-agent4j-tools-search

The `search` tool kind: web search through a provider you choose and pay for, with every result kept as untrusted text.
Add it when a workflow researches the web and the built-in `web_search` (DuckDuckGo, no key) is not enough: a provider
with a key gives steadier results, quotas and terms you have agreed to.

It adds no dependency beyond the core, the light [`ai-agent4j-tools`](../../ai-agent4j-tools/) module and Jackson.

## Providers

No provider is built in or chosen for you: a provider means a key, terms of use and a cost, and those are yours to choose.
The script names one with `provider:`.

| `provider:` | Service | Key |
|---|---|---|
| `brave` | The [Brave Search API](https://brave.com/search/api/) (the reference provider, `BraveSearch`) | Yours, from Brave |

Another service is one class implementing `SearchProvider` (a name, the one host it calls, and a `search` method that
sends its requests through the `HttpSupport` it is given), listed in
`META-INF/services/io.github.llm4j.tools.search.SearchProvider` of your jar. `BraveSearch` is about sixty lines. For tests,
`FakeSearch` answers every query with three made-up pages; list it in your test resources' services file and use
`provider: fake`.

## From a Loom script

```text
tool Web { use: search  provider: brave  api_key: secret.BRAVE_SEARCH_KEY  max_results: 5 }
agent Researcher { model: "gemini-2.5-flash"  system: "Find sources; cite every claim."  tools: [Web] }
```

## From Java

```java
Tool web = new SearchKind().create("Web", Map.of(
        "provider", "brave", "api_key", System.getenv("BRAVE_SEARCH_KEY"), "max_results", "5"), Path.of("."));
String results = web.execute(Map.of("query", "java records tutorial"));
```

`new SearchKind().check(options, baseDir)` says what is wrong with the options (an unknown provider names the ones it
found) without touching the network.

## Options and arguments

| Option | Meaning |
|---|---|
| `provider` | **Required.** The provider's name |
| `api_key` | The key, as a secret (`secret.NAME` or `env.NAME`); required by a provider that needs one. Never shown in a result, trace or error |
| `max_results` | Most results per query, default 5, at most 20. An agent can ask for fewer, never more |
| `timeout` | Time for one query, default 20s, at most 60s |

The agent gives `query` (at most 400 characters) and optionally `max_results`. The result is
`{"query", "provider", "results": [{"title", "url", "snippet"}]}`, titles cut at 200 characters and snippets at 500.

## What it reaches, and what the audit sees

- **One host:** the tool reaches the provider's host over HTTPS and nothing else, with the same SSRF, size and time guards
  as every network tool; a provider that tried to call another host would be refused.
- **Untrusted results:** `weave audit` sees a tool that reads untrusted content ("web search results from brave"), and
  the whole result stays unchecked model-reachable text: a page can carry instructions. Combine it with private data and
  a way out only behind an approval (the lethal trifecta, LA01).
- **The query leaves the machine:** the provider sees every query the model writes. Keep personal data out of an agent
  that searches, or mask it (`guard { pii: mask }`).
- A search changes nothing, so it needs no approval.

## Adding it

To a Maven project:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-tools-search</artifactId>
    <version>6.2.0</version>
</dependency>
```

To `weave`: put the jar on `--classes` (`weave run flow.loom --classes ai-agent4j-tools-search-6.2.0.jar`); the kind is
found when the jar is there, and `weave check` lists it with where it came from.
