# Verification Plan: Loom Capability Parity (P0 + P1)

Every criterion is a test with exact expected values. No network: models are scripted, embeddings come
from a deterministic `HashingEmbeddingProvider`, HTTP tools run against MockWebServer, and the environment
is an injected map.

## 1. Nothing silently ignored (P0)

| ID | Script | Then |
|---|---|---|
| V1.1 | agent with `memory: { type: "file" … }` at line 5 | `LoomLoadException` containing `line 5`, "agent memory is not supported yet" |
| V1.2 | `guardrail TOXICITY { … }` | error listing supported types (`PII`) |
| V1.3 | `tools: [Nope]` on agent A | error "agent A: tool Nope is not defined" and the resolution sources |
| V1.4 | `knowledge: [Missing]`, `routing: Missing`, `mcp_servers: [Missing]` | one error each, all three reported together |
| V1.5 | `mcp S { cmd: "definitely-not-a-command" }` used by an agent | `initialize()` fails naming S |
| V1.6 | V1.1 + V1.2 with `setLenient(true)` | no exception; two warnings logged |
| V1.7 | V1.3 with lenient | still an error |
| V1.8 | a script with 3 different problems | the exception message lists all 3, each with its line |

## 2. Honour what's written (P0)

| ID | Given | Then |
|---|---|---|
| V2.1 | agent with `persona: "…"` + `skills: ["classpath://…"]` | the system prompt contains the persona text **and** the skill section |
| V2.2 | same with `system_template` + a PromptRegistry | template text + skill section |
| V2.3 | `skills: ["fs://missing.md"]` | load error naming the skill |
| V2.4 | `routing R { strategy: "fallback" primary: "m1" fallbacks: ["m2","m3"] }`; m1 throws | m2 is called next, m3 not called |
| V2.5 | `strategy: "COST-AWARE"` | accepted (normalised) |
| V2.6 | `strategy: "random"` | load error listing cost_aware, fallback |

## 3. weave check (P0)

| ID | Given | Then |
|---|---|---|
| V3.1 | a valid script | prints ✓, exit 0, zero model/embedding/MCP calls |
| V3.2 | the V1.8 script | prints 3 problems with lines, exit 2 |
| V3.3 | `api_key: env.SERPAPI_KEY` with the variable unset | "SERPAPI_KEY is not set", exit 2 |
| V3.4 | variable set to `sk-secret-123` and another error present | output never contains `sk-secret-123` |
| V3.5 | `--lenient` with memory block | warning, exit 0 |

## 4. Tools (P1)

| ID | Given | Then |
|---|---|---|
| V4.1 | `tool Search { use: serpapi api_key: env.K base_url: "<mock>" }`, env K=abc; agent calls Search | MockWebServer request carries `api_key=abc`; the agent's observation contains the mocked result |
| V4.2 | `api_key: "abc"` (literal) | load error "api_key must come from the environment, e.g. api_key: env.SERPAPI_KEY" |
| V4.3 | `use: serpapi` without api_key; `use: teleport`; `colour: red` | three errors naming tool and line |
| V4.4 | `tools: [web_search, calculator, datetime, current_time]` with no declarations or .loot | agent has 4 tools named exactly so; `web_search` hits DuckDuckGo (MockWebServer via a declared `base_url` override in a sibling test) |
| V4.5 | `tool Petstore { use: openapi spec: "<mock>/openapi.json" auth_header: "X-API-Key" auth_value: env.P }` | a call hits the mocked endpoint with header `X-API-Key: <P>` |
| V4.6 | `auth_value` without header/query, or with both | load error |
| V4.7 | `use: class class: "io.github.llm4j.loom.execution.MockTool"` | tool works; a class not implementing Tool → error |
| V4.8 | declaration named `calculator` shadowing the built-in | the declared tool is used |
| V4.9 | existing `.loot` scripts (current tests) | unchanged behaviour |
| V4.10 | the model sees tool names | `Search`, not `WebSearch` (NamedTool) |

## 5. Knowledge (P1)

Fixture: `kb/` with `refunds.md` ("Refunds are issued within 14 days…"), `shipping.txt`
("Orders ship in 2 business days…"), `page.html` (`<p>Returns need a receipt</p>`) and `logo.png`.

| ID | Given | Then |
|---|---|---|
| V5.1 | `knowledge KB { source: "kb" chunk_size: 200 overlap: 20 embedding: "test/hash" top_k: 1 }`, agent `knowledge: [KB]`, delegate "How long do refunds take?" | the agent's context starts with "Relevant knowledge" and contains `(refunds.md)` and "14 days"; not shipping.txt |
| V5.2 | same, task "When will my order ship?" | contains `(shipping.txt)` |
| V5.3 | html file | chunk text is "Returns need a receipt" (no tags); png skipped, `knowledge_indexed` reports skipped=1 |
| V5.4 | `store: "idx/kb.json"`; load twice | second load embeds 0 chunks |
| V5.5 | edit refunds.md, reload | only refunds.md chunks re-embedded (count = its chunks); shipping untouched |
| V5.6 | delete shipping.txt, reload | its chunk ids gone from the store; retrieval never returns it |
| V5.7 | change chunk_size or embedding, reload | full rebuild (all chunks embedded) |
| V5.8 | `mode: tool` | agent has tool `search_kb`; calling it with query returns top chunk with source; context has no "Relevant knowledge" |
| V5.9 | replayed delegate (journal has the step) | zero embedding calls on replay |
| V5.10 | legacy `path:` + `type: "RAG"` | parses; deprecation warning; works as source |
| V5.11 | source missing; `embedding: "nope/x"`; `onnx/…` without addons (factory stub) | load errors naming the fix |
| V5.12 | KB with only png files | warning; delegate runs with no knowledge section |
| V5.13 | budget declared; KB used | budget spent counts only LLM calls (embedding calls not charged) |

## 6. Approvals (P1)

| ID | Given | Then |
|---|---|---|
| V6.1 | agent with tools [Publish, calculator], `approve: [Publish]`; model calls Publish; human answers "yes" | Publish executed once; audit approval_requested + approval_granted |
| V6.2 | human answers "no" | Publish not executed; the agent's next observation contains "rejected"; audit approval_rejected |
| V6.3 | calculator call | no question asked |
| V6.4 | durable journal + suspending HumanInterface | `RunSuspended` (HUMAN) with key `…#approve:Publish:<hash>`; nothing executed; after `journal.answer(key,"yes")` and resume, Publish executed exactly once, no second question |
| V6.5 | resumed step proposes Publish with different args | asked again (new key) |
| V6.6 | same args in a different key order | same key (canonical JSON) |
| V6.7 | `approve: [Nope]`; `approve` with no human interface | load errors |
| V6.8 | `approve: all` | every tool call asks |
| V6.9 | args contain an email address | audit data has it masked |

## 7. Agent settings (P1)

| ID | Given | Then |
|---|---|---|
| V7.1 | `max_iterations: 2`, model never answers finally | the agent stops after 2 LLM calls |
| V7.2 | `max_iterations: 0` | load error |
| V7.3 | script `audit { logger: "file" path: … }`, agent uses a tool | the audit file contains the agent's tool-execution event with the run's session id |

## 8. Compatibility and docs

- **N1.** All existing suites pass (ai-agent4j, addons, eval4j, Loom, Engram, GetViral).
- **N2.** `weave check` passes on every `.loom` file in the repository after migration (a test walks them).
- **N3.** The guide's new examples, and the README snippets, parse (`DocumentedExamplesTest`).
- **N4.** No new runtime dependencies (the pom diff shows only test scope).
- **N5.** Coverage of the new classes (validator, tools, knowledge, approvals) ≥ 90% lines / 85% branches.

## 9. Live check (needs your key)

- **L1.** A `gemini/text-embedding-004` knowledge base over a real folder answers a question citing the
  right file.

## 10. Phase gates

- **P0 done:** V1–V3, N1, N2; docs corrected.
- **P1 done:** V4–V7, N1–N5.
