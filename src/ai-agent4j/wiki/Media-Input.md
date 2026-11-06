# Media input: pictures, PDFs, sound and video

A message can carry more than text. Give the model a screenshot, a scanned form, a recording or a clip beside your question, and it answers about what it sees and hears. ai-agent4j sends it the same way to every provider that can take it, and **refuses before any call** when the model cannot, so media is never silently dropped.

## Attach something to a message

```java
MediaPolicy policy = MediaPolicy.defaults();
Media receipt = Media.image(receiptFile, policy);

LLMResponse reply = client.chat(LLMRequest.builder()
        .addMessage(Message.user("What is the total on this receipt?", List.of(receipt)))
        .build());
```

Here `receiptFile` is the `Path` of a picture. `Media.image`, `Media.audio`, `Media.video` and `Media.document` (a PDF) read a file. `Media.of(kind, bytes, name, policy)` takes bytes you already have. Only a user message can carry media; an assistant, system or tool message that tries is refused.

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

## Ask an agent about it

```java
AgentResult answer = agent.run("What is the total on this receipt?", List.of(receipt));
```

The media goes with the first user message of the run and stays there for every later step, so the agent can look again after calling a tool. Compaction keeps that first message, so a long run does not lose its picture. If the model cannot take the kind, it is never called. Run hooks and personal-data guards see the question only, not the media. To send more than the default policy allows (4 items, 32 MiB), give the agent a `MediaPolicy` with `ReActAgent.builder().mediaPolicy(...)`.

When compaction does have to remove a message that carried media (an older user message, not the task), it says so instead of letting the picture vanish: the marker reads `[12 earlier messages were removed to save space, including 2 images that can no longer be seen.]` and a summary ends with `[Media that went with these steps and can no longer be seen: 2 images.]`.

## In a Loom workflow

A Loom script attaches files with a trailing `attach` clause, and an extractor takes a file as an input of `from`:

```loom
media_root: "./assets"

agent Vision {
    model: "gemini-2.5-flash"
    system: "You read receipts."
}

workflow Expense(receipt) {
    delegate "What is the total and the currency?" to Vision attach image "{receipt}" -> reading
    return reading
}
```

Files are read only from `media_root`; a kind the agent's model cannot take is a load error from `weave check`; `weave audit` rule LA23 flags a file chosen at run time. The whole language side, with its limits and the `media: unscanned` opt-in for agents with a PII guard, is in the [Loom guide](../../loom/ai-agent4j-loom/LOOM_GUIDE.md).

## Personal data

`MaskingLLMClient` replaces emails, phone numbers and the like with placeholders, but only in text. It cannot see a name on a photographed form or a spoken card number, so **it refuses a request that carries media** and tells you why. If sending the media unmasked is acceptable, say so:

```java
LLMClient client = new MaskingLLMClient(base).allowUnscannedMedia();
```

The text of such a request is still masked; only the media goes through as it is.

## Budgets and context windows

A budget and a context window count media, or a request full of pictures would slip past both. The default estimate (`MediaTokens`) is about 1,600 tokens a picture, 3,000 or more a document, and sound and video by size, always on the high side. After the call the provider's reported usage replaces the estimate. For a different rule, give the agent or the budgeted client your own `TokenEstimator`.

## Limits

A `MediaPolicy` holds the limits, and `MediaPolicy.defaults()` is 20 MiB per item, 4 items per message and 32 MiB per request. Change them for one request with `LLMRequest.builder().mediaPolicy(...)`. Gemini takes at most 20 MiB of inline media in one request, and the Google provider refuses more before sending it. You can narrow what is allowed (`MediaPolicy.builder().allow(MediaKind.IMAGE, "image/png")`) or refuse a kind outright (`.refuse(MediaKind.VIDEO)`).

## What is never written down

A `Media` prints as `media:image/png:182340B:ab12cd34` (type, size, a short hash), never its content or its path. In JSON, logs, traces and journals it is a descriptor: kind, type, size, SHA-256 and file name. It is not `Serializable`. Reading a message back from JSON leaves its media empty, because a descriptor cannot rebuild the bytes; the caller reloads them and can check the SHA-256.

## What this does not do

- **It does not read the media for you.** The model does, and it can be wrong; measure it as you would any answer ([evaluation](../../eval4j/README.md)).
- **It does not mask personal data in a picture or a recording.** `MaskingLLMClient` works on text, which is why it refuses media unless told otherwise (above). Treat media as unscanned.
- **It does not fetch URLs.** Media comes from a file or from bytes you hold. A path that comes from outside your program must be checked by you (it is not confined to a directory here).
- **Media costs tokens.** A picture, a page, a second of sound or video is counted by the provider, and a budget's estimate for it is rough (above); the provider's reported usage replaces it after the call.
- **Prompt injection can arrive as pixels or speech.** Text in a picture or words in a recording can instruct the model like any other input. Do not give an agent that reads untrusted media a way to act on its own.

See also [Providers and the uniform contract](Providers-and-the-Uniform-Contract.md) and, for prompt caching, [the same page](Providers-and-the-Uniform-Contract.md#prompt-caching).
