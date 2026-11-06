# Media input: pictures, PDFs, sound and video

A message can carry more than text. Give the model a screenshot, a scanned form, a recording or a clip beside your question, and it answers about what it sees and hears. ai-agent4j sends it the same way to every provider that can take it, and **refuses before any call** when the model cannot, so media is never silently dropped.

## Attach something to a message

```java
MediaPolicy policy = MediaPolicy.defaults();
Media receipt = Media.image(Path.of("receipt.png"), policy);

LLMResponse reply = client.chat(LLMRequest.builder()
        .addMessage(Message.user("What is the total on this receipt?", List.of(receipt)))
        .build());
```

`Media.image`, `Media.audio`, `Media.video` and `Media.document` (a PDF) read a file. `Media.of(kind, bytes, name, policy)` takes bytes you already have. Only a user message can carry media; an assistant, system or tool message that tries is refused.

The type is decided from the **bytes**, never from the file name or from what you call it. A PDF handed over as an image is refused, so is an empty file, a file over the limit, a format the library does not know, and SVG (it is text that can carry script). The error says which.

## What each provider takes

Confirmed means a real call on 2026-11-06 returned the right answer ([the record](../../../docs/specs/31-multimodal-input/VERIFICATION.md)). The others follow the vendors' documentation and are tested against the request they must send, not yet against the real API.

| Provider | Pictures | PDFs | Sound | Video |
|---|---|---|---|---|
| Anthropic (every Claude model) | confirmed | confirmed | no (the API rejects it) | no (the API rejects it) |
| Google Gemini (not the `-tts` models) | confirmed | confirmed | confirmed (WAV, MP3) | confirmed (MP4) |
| OpenAI chat completions | confirmed (`gpt-4o`, `gpt-4.1`, `gpt-5` and later, `o1`, `o3`, `o4`) | confirmed (same models) | confirmed on `gpt-audio` models only, WAV and MP3 | no |
| OpenAI Responses | confirmed | confirmed | no (chat completions only) | no |
| xAI (Grok 4 family, Grok 2 vision) | confirmed | no | no | no |
| Kimi (vision models), Qwen (`-vl` models) | documented | no | no | no |
| Ollama (vision models such as `gemma3`, `llava`) | documented | no | no | no |
| Sarvam, Perplexity, any generic OpenAI-style server | no | no | no | no |

What a model takes is recorded **by model name**, and a model nobody recorded takes nothing: a new model is refused until it is added, which is the safe direction. Ask first:

```java
MediaCapabilities caps = client.mediaCapabilities("gpt-5-mini");   // null: the model this client is configured with
boolean sees = caps.accepts(MediaKind.IMAGE);
```

When a request carries something the model cannot take, `chat` throws an `InvalidRequestException` such as `anthropic claude-sonnet-5-5 does not accept video; accepted: image, document` and sends nothing. The wrappers (budgets, masking, routing) pass the question on; a router only claims what **every** client behind it takes.

## Limits

A `MediaPolicy` holds the limits, and `MediaPolicy.defaults()` is 20 MiB per item, 4 items per message and 32 MiB per request. Change them for one request with `LLMRequest.builder().mediaPolicy(...)`. Gemini takes at most 20 MiB of inline media in one request, and the Google provider refuses more before sending it. You can narrow what is allowed (`MediaPolicy.builder().allow(MediaKind.IMAGE, "image/png")`) or refuse a kind outright (`.refuse(MediaKind.VIDEO)`).

## What is never written down

A `Media` prints as `media:image/png:182340B:ab12cd34` (type, size, a short hash), never its content or its path. In JSON, logs, traces and journals it is a descriptor: kind, type, size, SHA-256 and file name. It is not `Serializable`. Reading a message back from JSON leaves its media empty, because a descriptor cannot rebuild the bytes; the caller reloads them and can check the SHA-256.

## What this does not do

- **It does not read the media for you.** The model does, and it can be wrong; measure it as you would any answer ([evaluation](../../eval4j/README.md)).
- **It does not mask personal data in a picture or a recording.** `MaskingLLMClient` and a Loom `guard { pii: mask }` work on text. Treat media as unscanned.
- **It does not fetch URLs.** Media comes from a file or from bytes you hold. A path that comes from outside your program must be checked by you (it is not confined to a directory here).
- **Media costs tokens.** A picture, a page, a second of sound or video is counted by the provider, and a budget's estimate for it is rough; the provider's reported usage replaces it after the call.
- **Prompt injection can arrive as pixels or speech.** Text in a picture or words in a recording can instruct the model like any other input. Do not give an agent that reads untrusted media a way to act on its own.

See also [Providers and the uniform contract](Providers-and-the-Uniform-Contract.md) and, for prompt caching, [the same page](Providers-and-the-Uniform-Contract.md#prompt-caching).
