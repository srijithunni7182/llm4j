# Native Tool Calling

A `ReActAgent` can ask a model to use tools in two ways:

| | Native tool calling | Text protocol |
|---|---|---|
| How the model is told about tools | as function definitions in the API request | in the system prompt |
| How the model asks for one | a structured tool call in the response | a JSON block it writes, which the agent parses |
| Arguments | the model's own JSON, validated against your schema by the provider | whatever parses |
| Works with | Gemini and Claude | every provider, including Sarvam and Ollama |

Native calling is the more reliable path: nothing is parsed out of prose, so a stray code fence, CRLF line endings or a multi-line
argument cannot break a step. **It is the default** (`ToolCalling.AUTO`) whenever it can be used.

## When the agent uses which

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .toolCalling(ReActAgent.ToolCalling.AUTO)   // the default; NATIVE or TEXT to force one
        .build();
```

| Mode | Behaviour |
|---|---|
| `AUTO` (default) | Native if the client supports it, the agent has tools, every tool name is a legal function name (letters, digits, `_`, `-`, at most 64), and you did not set your own `systemPrompt` or prompt template. Otherwise the text protocol. |
| `NATIVE` | Always native. Building the agent fails, with the reason, if the client or the tools cannot support it. A `systemPrompt` you set is used as given. |
| `TEXT` | The text protocol, exactly as before. |

A prompt of your own decides the protocol, so a custom `systemPrompt` keeps the text loop in `AUTO`. `instructions(...)`, personas and
skills work in both modes.

Check support with `client.supportsToolCalling()`. Gemini and Claude return true; Sarvam and Ollama return false. Clients that wrap them
(`BudgetedLLMClient`, `MaskingLLMClient`) pass the answer through, and `RoutingLLMClient` answers true only when every client it routes to does.
Sending tools to a client that cannot honour them fails with an `InvalidRequestException`; it is never silently ignored.

## Give your tools real schemas

A model can only fill in arguments it knows about. Declare them:

```java
public class RefundTool implements Tool {
    public String getName()        { return "refund"; }
    public String getDescription() { return "Refund an order."; }

    @Override
    public Map<String, Object> getParametersSchema() {
        return ToolSchema.object()
                .string("orderId", "The order to refund, e.g. A-17", true)
                .number("amount", "How much to refund", true)
                .build();
    }

    public String execute(Map<String, Object> args) { /* args.get("orderId"), args.get("amount") */ }
}
```

`ToolSchema` covers strings, numbers, integers, booleans, enumerations and lists of strings; any JSON Schema object works too. A tool that
declares nothing accepts any object, so the model has to guess argument names from the description (as it did in the text protocol). Gemini
refuses an object with no properties, so for such tools it is offered one free-form `input` string, and the agent unwraps a JSON object
written inside it. The built-in tools, MCP tools (from the server's own schema) and the generic `webhook`, `email`, `file`, `shell` and `sql`
tools declare theirs; `http` and `openapi` keep the permissive default.

## What stays the same

Native calling changes how the model and the agent talk, not what the agent does. Iteration limits, budgets and rate limits, approvals
(`requiresApproval`), duplicate-action blocking, listeners, the audit log, memory, conversation history, PII masking and the `AgentResult`
(steps with their `StepOutcome`, usage, confidence) are the same. A reply with no tool calls is the final answer. Every call the model makes
gets a result, including calls to unknown tools, blocked duplicates and rejected approvals, so the conversation stays valid. When a turn
holds several calls they run in order.

`protocolFollowed` is always true on native calling.

## Under the hood

- `LLMRequest.tools(...)` carries `ToolSpec`s (name, description, JSON Schema); `LLMResponse.getToolCalls()` returns `ToolCall`s.
- `Message.assistantToolCalls(...)` and `Message.toolResult(...)` build the tool turns of a conversation (`Role.TOOL`).
- Claude's thinking blocks and Gemini's thought signatures must come back with the tool results. The provider keeps what it received in
  `LLMResponse.getProviderData()`; the agent copies it onto the assistant message and the provider sends it back verbatim.
- `LLMRequest.toBuilder()` copies a request completely, which is what wrappers should use.

## Also fixed: the text protocol

For everything that still uses the text protocol (Sarvam, Ollama, custom prompts, `TEXT` mode), parsing is now tolerant: ` ```JSON `,
CRLF line endings, spaces after the fence, a bare JSON reply, and a multi-line `Action Input:` all work. And the confidence score now counts
a step as a failure by its `StepOutcome` (an exception, an unknown tool, a blocked duplicate), not by whether the tool's reply text begins
with "Error".

## Verification, honestly

The request and response formats for Gemini and Claude are tested on every build against recorded-shape responses from a local mock server,
including thinking blocks, thought signatures and merged tool results. Two live tests (`LiveNativeToolCallingTest`, L12 and L13) check the
same against the real services; they run with `mvn -pl ai-agent4j -Plive test` and your keys, and have not yet been run by the maintainer.
Parallel calls in one turn run sequentially, and streaming does not carry tool calls.
