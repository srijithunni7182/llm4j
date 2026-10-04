# Native tool calling, tolerant text parsing, outcome-based confidence, CI for every module: design

Status: implemented; see verification.md for the evidence. Agreed in conversation: "quick fixes + native tool calling + CI change; no change in banner wording"; tantrik is excluded from CI because it is being removed. Companion documents: [test-strategy.md](test-strategy.md), [verification.md](verification.md).

## 1. Problem

A review found four things, all confirmed against the code:

1. **Text-parsed ReAct is brittle.** `ReActAgent` asks the model for a fenced JSON block and matches `` ```json\n(.*?)\n``` ``. ` ```JSON `, CRLF line endings, a space before the newline or a bare JSON reply all miss it. The `Action:` / `Action Input:` fallback stops the input at the first newline, so a multi-line input is truncated. Gemini and Claude both have structured tool use, and `LLMResponse.FinishReason` already maps `tool_use` and `function_call`, but nothing sends tool definitions or reads tool calls.
2. **The confidence score string-matches `"Error"`** in observations although `AgentResult.StepOutcome` records what happened.
3. **Loom, Engram, Tantrik and the CTK never run in CI.**
4. (Out of scope by decision: the xAI banner wording stays as is.)

## 2. Goals and non-goals

Goals: native tool calling in the agent loop for providers that support it, with the text protocol kept as an explicit fallback; tolerant text parsing for everything that still uses it; confidence from outcomes; every module's tests gate merges in GitHub Actions and Jenkins.

Non-goals: native calling for Sarvam and Ollama (they stay on the text protocol, see 4.3); streaming tool calls; parallel execution of one turn's calls (they run in order); changing the xAI wording; calibrating confidence against datasets (a later piece of work).

## 3. Model (`io.github.llm4j.model`)  (TCM)

- **TCM-01** `ToolSpec(name, description, parameters)`: a tool as sent to a model; `parameters` is a JSON Schema object. Name must match `[A-Za-z0-9_-]{1,64}` (the common denominator of Gemini and Claude).
- **TCM-02** `ToolCall(id, name, arguments)`: what the model asked for. `id` may be null (Gemini sends none); the agent assigns `call_<n>`.
- **TCM-03** `Message` gains `Role.TOOL`, `toolCalls` (on assistant messages), `toolCallId` and `name` (on tool results) and an opaque `providerData` map. Factories `Message.assistantToolCalls(text, calls, providerData)` and `Message.toolResult(callId, name, content)`. The 3-argument constructor and JSON shape are unchanged for plain messages.
- **TCM-04** `LLMRequest` gains `tools` and `toolChoice` (`AUTO`, `NONE`) and `toBuilder()`, so wrappers copy a request without dropping fields.
- **TCM-05** `LLMResponse` gains `toolCalls` and `hasToolCalls()`; `providerData` for what must be echoed back (Section 4.2).
- **TCM-06** `LLMClient.supportsToolCalling()` and `LLMProvider.supportsToolCalling()` default to `false`. A request that carries tools sent to something that cannot honour them fails with `InvalidRequestException` (never silently ignored).
- **TCM-07** `Tool.getParametersSchema()` defaults to a permissive object schema; `ToolSchema` is a small builder (`ToolSchema.object().string("query", "what to search", true).number(...)`). Built-in tools declare real schemas.

## 4. Providers  (TCP)

- **TCP-01 Google** sends `tools: [{functionDeclarations: [...]}]` (schema converted to Gemini's OpenAPI subset: no `additionalProperties`, uppercase-insensitive types are accepted as lowercase), `toolConfig.functionCallingConfig.mode` (`AUTO` / `NONE`). `functionCall` parts become `ToolCall`s; assistant tool-call turns are sent as `model` turns with `functionCall` parts, tool results as `user` turns with `functionResponse` parts (merged when consecutive, as Gemini requires).
- **TCP-02 Anthropic** sends `tools: [{name, description, input_schema}]`, `tool_choice`. `tool_use` blocks become `ToolCall`s; assistant tool-call turns carry `tool_use` blocks, tool results are `user` turns with `tool_result` blocks (all results of one assistant turn in one user message, as the API requires).
- **TCP-03 Round trip of provider-specific blocks.** Thinking blocks (Claude) and thought signatures (Gemini) must be sent back unchanged with the tool results. The provider stores the raw content it received in the response's `providerData`; the agent copies it onto the assistant message; the provider re-emits it verbatim when present. Nothing is interpreted.
- **TCP-04** Finish reason `TOOL_CALLS` whenever tool calls are present, whatever the provider's own value; `content` is the model's text (may be empty).
- **TCP-05** Sarvam and Ollama: `supportsToolCalling()` is false; the agent uses the text protocol.
- **TCP-06** `DefaultLLMClient`, `BudgetedLLMClient`, `MaskingLLMClient` and `RoutingLLMClient` delegate `supportsToolCalling()` (routing: true only when every client supports it) and pass tools through. Masking also masks tool-result text and tool-call argument strings; budgeting counts tool definitions in its estimate.

## 5. Agent  (TCA)

- **TCA-01** `ReActAgent.Builder.toolCalling(ToolCalling)`: `AUTO` (default), `NATIVE`, `TEXT`.
- **TCA-02** `AUTO` uses native calling when the client supports it, the agent has tools, every tool name is a legal function name, and the system prompt is the default one (a custom `systemPrompt` or registry template dictates its own protocol, so it keeps the text protocol). `NATIVE` insists (fails fast with a clear message if unsupported). `TEXT` is the previous behaviour exactly.
- **TCA-03 Loop.** The conversation is a message list: system, the user turn (memory/history context and question), then for each model turn an assistant message and one tool-result message per call. A reply with no tool calls is the final answer. Iteration limit, budgets, rate limits, approvals, duplicate-action blocking, listeners, audit log, conversation history, semantic memory, bias monitor and the result shape are unchanged. `protocolFollowed` is true.
- **TCA-04** Every tool call gets a tool-result message, including unknown tools, blocked duplicates, rejected approvals and tool errors, so the transcript stays valid.
- **TCA-05** Arguments reach the tool as the model's JSON object, unchanged; a call whose arguments are not an object is an `EXECUTION_ERROR` step.
- **TCA-06** An empty reply with no tool calls is not an answer: the agent adds a nudge and continues (counts as an iteration).
- **TCA-07** The native system prompt has no JSON protocol: persona, skills and instructions, the tool list is carried by the tool definitions, and a short note to answer directly when no tool is needed.

## 6. Text protocol  (TCF)

- **TCF-01** Fence: any case of `json`, optional spaces, LF or CRLF, closing fence with or without a preceding newline; a bare JSON object reply is accepted.
- **TCF-02** `Action Input:` runs until the next `Observation:`, `Thought:`, `Plan:`, `Action:` or `Final Answer:` line (or the end), so multi-line inputs survive; CRLF is normalised.
- **TCF-03** Nothing that parsed before stops parsing.

## 7. Confidence  (TCC)

- **TCC-01** A step counts as a failure by `StepOutcome` (`EXECUTION_ERROR`, `UNKNOWN_TOOL`, `DUPLICATE_BLOCKED`, `APPROVAL_UNAVAILABLE`), not by its text. A tool that legitimately returns text beginning "Error" is not penalised; `REJECTED_BY_HUMAN` is a decision, not a failure. Weights and the other heuristics are unchanged. (The banner wording is unchanged by decision.)

## 8. CI  (CI)

- **CI-01** GitHub Actions runs the tests of `loom/ai-agent4j-loom`, `engram`, `tantrik`, `eval4j-report` and `loom/ctk` as well as the four modules it already runs, on every push and pull request.
- **CI-02** The Jenkinsfile does the same (build, test with reports, and the existing quality stages where they apply).
- **CI-03** A test fails the build if a module with tests is missing from the GitHub workflow (`CiCoversEveryModuleTest`), so a new module cannot silently skip CI.

## 9. Compatibility

Additive API; existing `Tool` implementations work (permissive schema). Behaviour change: with Gemini or Claude and the default prompt, agents now use native calling by default (`toolCalling(TEXT)` restores the old loop). Mock clients in tests do not support tool calling, so existing tests keep the text path.

## 10. Honest limits

Native calling is only as good as the tool schemas: tools that keep the permissive default rely on the model guessing argument names from the description (the text protocol had the same problem). Sarvam and Ollama stay on text. Gemini's OpenAPI subset rejects some JSON Schema keywords; the converter drops the ones known to be refused. Parallel calls in one turn run sequentially.

## 11. Deviations from the first design

- `tantrik` is exempt from the CI guard and not added to the pipelines (it is being removed).
- `engram` is built from the `engram` directory (aggregator), not module by module, since `engram-core` resolves its parent through it.
- Schemas were also added to the generic `webhook`, `email`, `file`, `shell` and `sql` tools and to the search, memory, scheduling, delegation and graph-query tools; `http` and `openapi` keep the permissive default because their arguments include free-form objects Gemini would refuse.
- `ReActAgent.Builder.toBuilder()` carries the native prompt and whether the prompt was the default, so a copied agent keeps its mode.
- Live tests L12 and L13 were added to the `-Plive` suite; they could not be run without keys.
