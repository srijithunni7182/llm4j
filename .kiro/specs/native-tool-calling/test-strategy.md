# Native tool calling: test strategy

Companion to [design.md](design.md). IDs: `TCM`, `TCP`, `TCA`, `TCF`, `TCC`, `CI`. [verification.md](verification.md) maps each to tests and records evidence (generated from surefire reports at the end).

## Approach

- **Providers** with `MockWebServer`: assert the exact JSON the provider sends (tools, tool choice, tool-call and tool-result turns, merging of consecutive turns) and parse recorded provider responses (single call, several calls, text plus call, thinking blocks and thought signatures round-tripped verbatim, no id).
- **Agent** with a scripted native client (`supportsToolCalling() == true`) that returns tool calls then an answer: assert the transcript the model sees on each turn, approvals, duplicate blocking, unknown tools, errors, empty reply, iteration limit, budgets (through `BudgetedLLMClient`), listeners, steps, confidence; modes `AUTO`/`NATIVE`/`TEXT` with supporting and non-supporting clients; custom system prompt keeps text.
- **End to end** through `DefaultLLMClient` + `GoogleProvider`/`AnthropicProvider` against `MockWebServer`: a two-turn tool conversation.
- **Text tolerance**: a table of reply variants (` ```JSON `, CRLF, trailing spaces, bare JSON, multi-line `Action Input:`, `Final Answer:` with CRLF) parsed to the same result.
- **Confidence**: outcome-based cases, including a tool that legitimately returns "Error: ..." text (not penalised) and a thrown exception (penalised).
- **Wrappers**: masking masks tool results and keeps tool fields; budgeted and masking copies preserve `tools`; routing support is the conjunction.
- **Regression**: every existing suite (ai-agent4j, addons, Loom, eval4j, eval4j-report, CTK) unchanged and green.
- **CI**: a test parses the GitHub workflow and the Jenkinsfile and asserts every module with a `src/test` directory is built and tested.
- **Negative controls**: drop the tool-result message for blocked calls (transcript invalid), stop round-tripping `providerData`, revert the fence regex, revert confidence to string matching; each must fail its named test.

## Exit criteria

Every requirement ID `Verified`; every touched module's full suite green; negative controls recorded.
