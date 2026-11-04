# Structured output: replies that follow a schema

A model can be asked for JSON in the prompt, or the provider can be told to **hold the reply to a JSON Schema** while it writes. The second is the more reliable primitive: the provider decodes within the schema, so the reply has the fields you declared, with the types you declared, instead of whatever the model felt like writing. ai-agent4j lets you ask for it the same way on every provider that supports it, and Loom's `extractor` uses it automatically ([how](../../loom/ai-agent4j-loom/LOOM_GUIDE.md#native-json-how-an-extractor-gets-structured-output)).

## Why it is more reliable

Asking in the prompt is a request. The model usually complies, and sometimes it wraps the JSON in a code fence, adds a sentence before it, leaves a field out, or runs on until the token limit. Every one of those is a failed call you pay for and a retry you have to write. A schema in the request moves the guarantee from the model's manners to the provider's decoder.

What the evidence in this repository shows, and what it does not:

- **24 live extractions on 8 provider routes** (OpenAI and xAI on chat completions and on the Responses API, Claude, Gemini, Perplexity's Agent API, and a local Ollama server) each returned the declared fields on the first attempt, with no correction and no fallback. That includes an input telling the model to ignore its instructions and say "HELLO". The record, call by call, is in [LIVE_FIXTURES.md](../../../docs/specs/30-native-json-output/LIVE_FIXTURES.md).
- **The clearest contrast** is a 135-million-parameter model on a local Ollama server, given the same prompt and the same schema twice. Without `format` it answered in a code fence, with the wrong shape, and ran to the length limit. With `format` it answered exactly the three fields, with an allowed enum value.
- **What is not shown:** a measured failure rate for prompt-only JSON on the larger models above. They usually obey a prompt anyway, so the gain there is mostly fewer correction calls and no parsing code; the gain is largest for small and local models, where the prompt route is the weak spot.

## Ask for a reply that follows a schema

```java
LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
ResponseFormat invoice = ResponseFormat.jsonSchema("invoice", Map.of(
        "type", "object",
        "properties", Map.of(
                "vendor", Map.of("type", "string"),
                "total", Map.of("type", List.of("number", "null"))),   // optional: may be null
        "required", List.of("vendor", "total"),                      // strict mode wants every field listed
        "additionalProperties", false));

LLMResponse reply = client.chat(LLMRequest.builder()
        .model("gpt-4o-mini")
        .addUserMessage("ACME GmbH invoice INV-8. Thank you for your business.")
        .responseFormat(invoice)                  // sent only if the model is recorded as supporting it
        .build());
JsonNode fields = new ObjectMapper().readTree(reply.getContent());
// fields.get("vendor") is a string; fields.get("total") is a number or null
```

The schema is an ordinary JSON Schema whose root is an object. `ResponseFormat.jsonSchema(name, schema)` is strict by default, and refuses a blank name or a root that is not an object. The request is the same for every provider; each puts the schema where its own API takes it, and leaves it out when it cannot.

## Ask whether a model can be held to a schema

```java
StructuredOutput named = client.structuredOutput("gpt-4o");   // a model you name
StructuredOutput configured = client.structuredOutput(null);  // null: the model this client is configured with
// NONE means "not recorded as supported": ask for JSON in the prompt and check what comes back
```

Support is recorded **per provider and model**, from the vendors' own documentation, and an unknown model is never assumed to have it. A wrapper (`MaskingLLMClient`, `BudgetedLLMClient`, a router) answers for what it wraps; a router says yes only when every client it may use does.

## Which providers

| Provider | Where the schema goes | Held to a schema for |
|---|---|---|
| OpenAI, chat completions | `response_format.json_schema` | gpt-4o and later, o1, o3, o4 |
| OpenAI, Responses API | `text.format` | the same models |
| xAI, chat completions and Responses | the same two places | the Grok 4 family |
| Anthropic | `output_config.format` (shared with `effort`) | the models Anthropic's structured-outputs page lists |
| Google Gemini | `generationConfig.responseMimeType` and `responseJsonSchema` | Gemini 2.5 and later |
| Perplexity, Agent API | `response_format.json_schema` | any named model (not a preset) |
| Ollama | `format` | any model on a local server; **not** Ollama Cloud, which documents that it does not support it |
| Kimi, Qwen, other OpenAI-style servers | not sent | not recorded yet |

A provider you run yourself can declare support when you build its dialect (`Dialect.Builder.structuredOutputModels`).

## What a strict schema needs

One schema that every provider's strict rules accept is easy to write and hard to get wrong:

- list **every** field in `required`, and set `additionalProperties` to `false` at every level of nesting;
- write an optional field as its type or null (`["number", "null"]`), not as a field left out of `required`;
- stay with `string`, `number`, `integer`, `boolean`, enums of strings, arrays and nested objects. Leave out `minimum`, `maximum`, `minLength`, `maxLength`, `format` and recursion: some providers refuse them with a 400, and the SDK helpers of some strip them quietly;
- keep it small. Anthropic accepts at most 24 optional parameters (16 with a union type) across the strict schemas of one request, and OpenAI at most 10 levels of nesting.

## What you still check

A reply held to a schema is still model output. Check it before you act on it:

- **A refusal** may not match the schema (Anthropic reports `stop_reason: "refusal"`, which this library raises as a `ContentBlockedException`).
- **A reply cut at the token limit** is incomplete JSON. Give a structured call enough `maxTokens`.
- **The values** are the model's judgement. The schema guarantees a total is a number or null, not that it is the right number: the tiny local model above invented a total for an invoice that stated none.

A provider that rejects the schema answers with a 400. Treat that as "ask again in the prompt", which is what Loom's `extractor` does, once, and then remembers for the rest of the run.

## In Loom

You do not write any of this in a workflow. An `extractor` builds its schema from its own fields and asks for it whenever the provider can, and the trace of every `extract` says which route ran. See [Native JSON: how an extractor gets structured output](../../loom/ai-agent4j-loom/LOOM_GUIDE.md#native-json-how-an-extractor-gets-structured-output).
