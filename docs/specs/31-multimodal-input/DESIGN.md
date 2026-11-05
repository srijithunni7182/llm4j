# Phase 31 design: prompt caching and multimodal input (ai-agent4j, Loom)

Status: **draft for review** (decisions D1 to D4 were taken by the owner; D5 to D10 are proposed and open). Layers: ai-agent4j (the message model, provider writers, guards,
accounting), Loom (the `attach` clause, the runtime, `weave check`, `weave audit`, the graph, the journal), docs and editor support.
Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md).
Builds on the provider SPI ([Phase 8](../08-providers-and-grounding/DESIGN.md)), native JSON output ([Phase 30](../30-native-json-output/DESIGN.md), the pattern for a per-model capability),
call telemetry and prices ([Phase 2](../02-call-telemetry/DESIGN.md)), context windows ([Phase 1](../01-context-management/DESIGN.md)).

The work is in two parts, in this order: **Part A** (prompt caching, one provider, small) and **Part B** (multimodal input: images, audio, video and PDF documents, from a Loom script and from Java).
One spec because both change what a request is made of and what it costs.

## 1. Problem

**Caching.** An agent loop resends the same system prompt, tool list and growing history on every turn. OpenAI and Gemini cache a long prefix on their own, and llm4j already reads
OpenAI's `usage.cached_tokens`. Anthropic caches only when the request marks a `cache_control` breakpoint, and `AnthropicProvider` never does; it only adds the cache counters to its
usage total (`cache_creation_input_tokens`, `cache_read_input_tokens`). So on Anthropic every turn pays the full input price, and the cost accounting cannot tell a cached read from a fresh one.

**Multimodal input.** `Message.content` is a non-null `String`. Every provider writer sends text only (Gemini `parts: [{"text"}]`, OpenAI `content` as a string, Anthropic text blocks,
Ollama a string). `additionalParameters` is copied to the top level of the request, and an attachment belongs inside a message, so it cannot carry one. The only call that takes a file is
`SpeechToTextProvider.transcribe(File, …)`, for audio, on Sarvam. The providers themselves accept images, PDFs, audio and (Gemini) video. So today a workflow cannot ask a model about a screenshot,
a chart, a scanned form, a recording or a clip, and a team that needs it leaves Loom.

In Loom the task a model receives is a string end to end: `DelegateStmt.payload` is a `String`, `ReActAgent.run(String)` takes a `String`, and the runtime's `DelegateRunner` hands that string to the agent.
So media has to be added to the language, the runtime and the agent, not only to the providers.

## 2. Goals and non-goals

**Goals**

- G1. Anthropic prompt caching, opt-in, with the savings visible in telemetry and budgets, and the same `usage.cached_tokens` name on every provider.
- G2. A typed way to put **images, audio, video and PDF documents** in a message, provider-neutral, immutable and bounded.
- G3. Every chat provider either writes the media in its own format or **refuses** it with a clear error. Media is never silently dropped and never sent in the wrong shape.
- G4. In Loom, an `attach` clause on every statement that sends a task to a model, with sources limited to files under a declared root and to paths held in workflow inputs.
- G5. **Fail closed, early**: a media type that a provider cannot take is an error at `weave check` when the agent and the attachment are statically known, and at call time otherwise, before any model call is paid for.
- G6. The guards keep their meaning: size and count limits, content sniffing, path containment, PII policy, secret hygiene, journalling that never repeats an effect, accounting that does not under-count.
- G7. No new runtime dependency in any published module.

**Non-goals**

- URL sources (`http(s)://…`) for media (D2). A later phase can add them through `NetPolicy`/`HttpSupport`; nothing here forbids it.
- Provider file stores (OpenAI/Anthropic/Gemini Files APIs). They save bandwidth, not tokens, and bring lifetimes, deletion and cost questions of their own.
- Media **output**: image generation, speech synthesis from a chat call, video generation. (`TextToSpeechProvider` stays as it is.)
- Live, bidirectional audio or video streaming (realtime APIs).
- Automatic transcription or captioning as a fallback (D3). A model that cannot take audio is refused, not worked around behind the author's back.
- Making a model's reading of the media **correct**. We deliver it faithfully and bound it; evaluation (eval4j) measures quality.
- Extracting text from documents (OCR, PDF to text). `RAGAgent` document loading is unchanged.

## 3. Part A: prompt caching

### 3.1 API

```java
LLMRequest request = LLMRequest.builder()
        .messages(history)
        .promptCache(PromptCache.builder().system().tools().throughMessage(history.size() - 2).build())
        .build();
```

`PromptCache` is an immutable value: which stable parts of the request may be cached. `system()` and `tools()` mark the end of the system block and of the tool list; `throughMessage(i)` marks
the end of message `i` (the end of the part that does not change between turns). Default: **no caching** (D5), because Anthropic bills a cache write above the normal input price and a one-shot
call never reads it back.

### 3.2 Behaviour

- **R1.** `AnthropicProvider` writes `"cache_control": {"type": "ephemeral"}` on the last block of each marked part. At most four breakpoints are written (the vendor's limit); more is an
  `IllegalArgumentException` when the `PromptCache` is built, not a surprise from the API.
- **R2.** Providers that cache by themselves (OpenAI family, Google) ignore `PromptCache`; they accept it and do nothing. An ignored hint is documented, not an error: the contract is "may cache".
- **R3.** Every provider reports cached input under the same metadata key, `usage.cached_tokens` (reads), and Anthropic also reports `usage.cache_write_tokens`. Google's cached count is read where its
  response carries one. Prompt tokens keep their meaning ("includes cached input", see the uniform contract page).
- **R4.** The price table and the budget accounting price a cache read and a cache write at their own rates, so a budget neither over-charges cached reads nor misses the write premium.
  A model with no cache price in the table is priced as before and the trace says so once.
- **R5.** `ReActAgent` turns on `system()` and `tools()` caching for its own loop when its model's provider supports it, because a loop is the case that reads the prefix back. A setting turns it off.
  In Loom, an agent option `cache: off` (the default is on for an agent that loops) is the only syntax (D6).
- **R6.** Caching changes cost, never answers. A test shows the request body differs only by the markers.

## 4. Part B: multimodal input

### 4.1 The model (package `io.github.llm4j.model`)

```java
Media photo = Media.image(Path.of("receipt.png"), policy);        // reads, sniffs, bounds
Media clip  = Media.of(MediaKind.VIDEO, bytes, "demo.mp4", policy);

Message m = Message.user("What is the total on this receipt?", List.of(photo));
```

- `MediaKind` is an enum: `IMAGE`, `AUDIO`, `VIDEO`, `DOCUMENT`. `DOCUMENT` means PDF only in this phase.
- `Media` is an immutable value: kind, sniffed `mediaType` (for example `image/png`), the bytes (defensively copied), a display `name`, and a SHA-256 of the bytes. Its `toString()` is
  `media:image/png:182340B:ab12cd34` (kind, type, size, short hash). It never prints the bytes or a path. It is not `Serializable`, and Jackson serialises only the descriptor (R9).
- `Message` gains `List<Media> media()` (empty by default). Existing constructors and `Message.user(String)` keep working and mean "no media" (`docs/API_COMPATIBILITY.md`: an added component
  keeps the old constructor). `getContent()` stays the text, so every reader of text (summariser, router, scratchpad, history) keeps working unchanged.
- `MediaPolicy` is an immutable limit set: maximum bytes per item, maximum items per message, maximum total bytes per request, the allowed media types per kind. Named constants give the defaults (D8).
  A policy with no allow list for a kind refuses that kind (fail closed).

### 4.2 Behaviour

- **R7. Sniff, don't trust.** `Media` determines the type from the bytes' magic numbers (PNG, JPEG, GIF, WebP, PDF, WAV, MP3, MP4, WebM, …), never from the file extension or a caller-supplied type. A kind that
  does not match what was sniffed is refused (a `.png` that is a PDF, a "video" that is a script). An unknown format is refused. The allow list is closed: SVG is not accepted as an image (it can carry script).
- **R8. Bounded.** `Media` creation and `LLMRequest` building enforce the `MediaPolicy`: per item, per message, per request. A file is size-checked before it is read into memory. The error says which limit and the figure.
- **R9. Serialisation.** The journal, traces, telemetry, eval reports and Jackson output carry the descriptor `{kind, mediaType, bytes, sha256, name}` and never the bytes. A replay that needs the bytes reloads them from
  their source and checks the SHA-256; a mismatch fails the step, it does not send different content under the old record.
- **R10. Capabilities.** A provider/model declares which kinds it accepts (`MediaCapabilities`, the same idea as Phase 30's structured-output capability, kept next to the model's traits). The initial table is in §4.3.
  `LLMClient.chat` with media the model cannot take throws `InvalidRequestException` ("anthropic claude-… does not accept video; accepted: image, document") **before** the HTTP call.
  Unknown models take the conservative answer for their provider family.
- **R11. Writers.** Each provider writes its own wire format (§4.3), tested against recorded fixtures. The writers add the media next to the text of the same message and keep the order the caller gave.
- **R12. Wrappers keep the contract.** `MaskingLLMClient`, `BudgetedLLMClient`, the routing and retry decorators pass media through. A decorator that cannot handle media must refuse, not drop it (Liskov).
- **R13. PII.** `MaskingLLMClient` and `guard { pii: … }` can inspect text only. With `pii: mask` or `pii: block`, a message carrying media is **refused** unless the script says the media is unscanned (D7). The refusal names the
  agent and the setting. This is the one place where the new feature meets an existing control, so it is specified, tested and audited explicitly.
- **R14. Accounting.** Budgets and the context-window check estimate media tokens per item from a documented table (an image by size, audio and video by duration, a document by pages when the vendor reports them,
  otherwise by bytes), conservatively high so that a budget is never silently breached. The vendor's reported usage replaces the estimate after the call. The estimates are named constants with the vendor figure they come from.
- **R15. Secrets.** No secret ever appears in media handling: no key in a path, no credential in a descriptor. Media bytes are never logged. A test fails if a byte of the payload reaches a log appender.

### 4.3 What each provider takes (to be confirmed by recorded fixtures in 31c)

| Provider | Image | Audio | Video | PDF | Wire shape |
|---|---|---|---|---|---|
| Anthropic | yes | no | no | yes | `image` / `document` blocks with a base64 `source` |
| OpenAI chat | yes (vision models) | some models only | no | via file part on some models | `content` array: `image_url` with a `data:` URI; `input_audio`; `file` |
| OpenAI Responses | yes | some models only | no | yes | `input_image`, `input_file`, `input_audio` items |
| xAI, Kimi, Qwen, generic OpenAI-compatible | vision-capable models | no | no | no | `image_url` as OpenAI chat; anything else is refused |
| Google (Gemini) | yes | yes | yes | yes | `inline_data {mime_type, data}` parts |
| Ollama | vision models | no | no | no | base64 `images` array on the message |
| Sarvam, Perplexity (router and decisions) | no | no | no | no | refused with a clear error |

This table is **recorded knowledge of the vendors' APIs, not yet verified in this repository**. Sub-phase 31c's first task is to confirm each cell against the vendor reference and a recorded fixture, and to correct this table
where the vendors differ. Where a model within a provider differs (for example OpenAI audio models), the capability lives in the model traits, not in the provider.

Inline base64 is the only delivery in this phase (D2). The request-size limits of the vendors (a few tens of megabytes) are part of the capability data, so a video that is too large for inline delivery is refused with that reason.

## 5. Part B: Loom

### 5.1 Syntax

A trailing `attach` clause on every statement that sends a task to a model:

```loom
agent Vision { model: "gemini-2.5-flash" system: "You read receipts." }

workflow Expense(receipt_path) {
    delegate "What is the total and the currency?" to Vision
        attach image "{receipt_path}", document "policy/limits.pdf" -> reading
    return reading
}
```

- `attach` takes one or more items, comma-separated: `image|audio|video|document "<path>"`. The path is a string like any other, so `{name}` substitution applies (and the `substitution` setting governs it).
- Order in a statement: payload, `to`, `attach …`, then `-> var`, then `expecting`, `retry`, and the rest, unchanged. `attach` before `->` reads left to right: what is sent, to whom, with what.
- The same clause is accepted on `broadcast` (the same media goes to every agent in the list), `handoff`, `extract`, `intentclassify`, a `run` step that names a model, and `human_prompt` (the person is shown the media, §5.4).
  `note` and `halt` record text and send nothing to a model, so they do not take `attach` (D9). The full list of statements is confirmed against the parser in 31e's first task.
- A script sets where files may live, once, at the top, like `substitution`:

```loom
media_root: "./assets"
media { max_bytes: 20MB  max_items: 4 }
```

  No `media_root` means no file attachments (fail closed). The root is resolved relative to the script and must exist.

### 5.2 Behaviour

- **R16. Sources.** A path is resolved against `media_root`, normalised, and checked to still be inside it; `..`, absolute paths outside the root and symlinks that leave the root are refused. This reuses the file tool's path guard
  (`PathGuard`/`SafePaths`) rather than writing a second one. A path built from a variable is checked after substitution. A model's output used as a path is untrusted input and gets the same check.
- **R17. Static checking (`weave check`).** For a literal `attach` and an agent whose model is known, the check reports: a media kind the model cannot take (error), a missing file or a path outside the root (error when the path is literal),
  a file larger than the limit (error), a missing `media_root` (error), `attach` on a statement that cannot take it, and an `attach` to a dynamic target `{x}` that cannot be checked (warning naming what was skipped). Messages say what to change.
- **R18. Runtime.** Before the agent runs: resolve, read, sniff, bound, check the capability, check the PII policy; only then the model call. A failure is a step failure like any other (it can have `retry` and `on_failure`; a missing file is
  not retried because it will not appear, which the error says).
- **R19. Agents.** `ReActAgent` gets a `run(String task, List<Media> media, RunHandle)` overload; the media rides on the **first user message** of the run and is kept in the conversation so later turns still see it. History compaction keeps the
  media of the first message and may drop media from compacted older turns, saying so in the summary ("2 images were removed"), because it is the one case where the loop cannot afford to resend it forever.
- **R20. Journal and replay.** The journal records the descriptor of every attachment (R9) with the step, never the bytes. Resume, rewind and fork never repeat an effect: media input is a read, so a replay reloads the file, checks the
  SHA-256 and fails the step if the file changed, rather than silently answering about different content. `weave eval` fingerprints include the SHA-256, so a changed file invalidates a cached result.
- **R21. Audit.** A new `weave audit` rule (the next free id; LA23 at the time of writing, listed in `docs/security/weave-audit.md` and mapped to LLM01 and LLM02) fires when an agent receives media that **comes from a variable**: a workflow
  input or a model's output can be an image or a recording that carries instructions (prompt injection through pixels, text in images, spoken commands), so it counts as reading untrusted content for the trifecta, as LA20 does for web
  grounding. A literal file under the root, written by the script's author, does not fire. Severity follows LA01's logic when the trifecta completes.
- **R22. Graph and Fabric.** The graph shows an attachment on the node (a badge with the kinds and count), `weave graph` output is regenerated with `scripts/sync-graph-render.sh`, and the inspect/Fabric run page shows the descriptor, not the bytes.
- **R23. Editor.** The VS Code grammar highlights `attach`, `media_root` and `media`, a snippet inserts the clause, and the language server completes the kinds. `CHANGELOG.md` records it.

### 5.3 Compatibility

A script with no `attach` parses, checks and runs exactly as before; `attach`, `media_root` and `media` become keywords only in the positions above, so an existing agent or variable of that name still works (the parser tests this).
Java callers that use `Message.user(String)` or `ReActAgent.run(String)` are unaffected. `API_COMPATIBILITY.md` lists the new public types as added.

### 5.4 Open point: a person as the receiver

`human_prompt … attach …` shows media to a person. Delivery to a person depends on the channel (terminal, Fabric, an embedding host). The statement is specified so that the runtime hands the descriptor and a
controlled way to read the bytes to the prompt channel; the terminal channel prints the descriptor and the path, Fabric shows the media. If Fabric display slips, the terminal behaviour ships and the gap is recorded in
VERIFICATION §7. Nothing else in this phase waits on it.

## 6. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | The Loom syntax is a trailing `attach` clause. | **taken** (owner) |
| D2 | Sources are local files under a root and paths held in workflow inputs; no URLs in this phase. | **taken** (owner) |
| D3 | A media type a provider cannot take fails closed, at `weave check` where it can be known; no automatic transcription or captioning. | **taken** (owner) |
| D4 | Delivered phase by phase: this spec first, then each sub-phase committed and pushed on its own. | **taken** (owner) |
| D5 | Caching is off by default in the library, on for `ReActAgent` loops. | proposed |
| D6 | The only Loom caching syntax is the agent option `cache: off`. | proposed |
| D7 | With `pii: mask` or `pii: block`, media is refused unless the script opts in with an explicit "unscanned" setting on the guard. Exact spelling is open. | proposed |
| D8 | Default limits: 20 MB per item, 4 items per message, 32 MB per request, image/audio/video/PDF allow lists as in §4.2 R7. To be checked against the vendors' limits in 31c. | proposed |
| D9 | `note` and `halt` do not take `attach`. | proposed |
| D10 | `DOCUMENT` means PDF only; Office formats are out of scope. | proposed |

## 7. Sub-phases

| Sub-phase | Delivers | Requirements |
|---|---|---|
| **31a** | `PromptCache`, Anthropic `cache_control`, cached-token metadata on every provider, cache prices in the table and budgets, `ReActAgent` default, Loom `cache: off` | R1 to R6 |
| **31b** | `MediaKind`, `Media`, `MediaPolicy`, `Message.media`, request validation, `MediaCapabilities`, serialisation as a descriptor, `toString`/log hygiene | R7 to R10, R15 |
| **31c** | The writers: Anthropic, OpenAI chat, OpenAI Responses, Google, Ollama, the OpenAI-compatible family, refusals for Sarvam and Perplexity; recorded fixtures; the §4.3 table confirmed | R11 |
| **31d** | Decorators, PII refusal, token estimates for budgets and windows, `ReActAgent.run` overload and compaction | R12 to R14, R19 |
| **31e** | Loom: AST, parser, `media_root`/`media`, source resolver, `weave check`, runtime, journal and replay, audit rule, graph, `human_prompt` | R16 to R22 |
| **31f** | Docs everywhere they live, the VS Code grammar and snippets, CTK scenarios, starters/tutorial where they apply, the skills, release notes, `API_COMPATIBILITY.md`, mutation profile | R23 and the documentation rules |

Each sub-phase is written test first, passes `mvn verify` on every module it touches, is committed and pushed on its own, and updates its row in [the roadmap](../README.md).
