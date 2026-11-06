# Phase 31 verification plan: prompt caching and multimodal input

Status: **not started**. Run §0 first, then top to bottom at the end of each sub-phase (**31a** caching, R1 to R6; **31b** the model and guards, R7 to R10, R15; **31c** the writers, R11; **31d** decorators, accounting and the
agent, R12 to R14, R19; **31e** Loom, R16 to R22; **31f** documentation, editor and the rest, R23). Results in §7. Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Before writing anything

| # | Task | Done when | Result |
|---|---|---|---|
| P1 | **The owner reviews this spec** and answers D5 to D10 (or accepts the proposals) | the decisions table in DESIGN §6 says taken for each | ⬜ |
| P2 | **Confirm the §4.3 table** against each vendor's current reference for image, audio, video and PDF input, and for the request-size limits | each cell is marked confirmed with the source and date, and the table is corrected where it was wrong | ⬜ |
| P3 | **Confirm the caching facts**: Anthropic breakpoint limit, minimum cacheable length, TTL and the price of a write and a read; what OpenAI and Gemini report | recorded in §7 with the date | ⬜ |
| P4 | **List the statements that send a task to a model** by reading `LoomParser` and the runners, to fix the exact list in DESIGN §5.1 | the list is in §7 and DESIGN §5.1 matches it | ⬜ |
| P5 | **Find every reader of `Message`** that would need to know about media (summariser, history, scratchpad, router, masking, journal, eval fingerprints) | each is marked "text only is correct", "pass through" or "must change" | ⬜ |
| P6 | **Baseline**: `mvn verify` on `ai-agent4j`, `ai-agent4j-loom` and the modules after them, and the mutation profiles, record the scores and any failure that exists before the change | recorded in §7, so a later failure is attributable | ⬜ |
| P7 | **The owner provides keys** (in their own environment, never in the chat) only if the live tests are to be run | recorded as run or not run | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | Anthropic requests carry `cache_control` where asked and nowhere else, at most four, and the savings show as `usage.cached_tokens` on every provider | R1 to R3 tests |
| A2 | Cache reads and writes are priced and budgeted correctly | R4 tests |
| A3 | `ReActAgent` caches its loop prefix by default where supported, and `cache: false` stops it | R5 tests |
| A4 | An image, audio, video or PDF can be put in a `Message` from Java, and the type is decided by the bytes | R7 tests |
| A5 | Size, count and type limits hold at every boundary, and a refusal says which | R8 tests |
| A6 | The bytes never appear in a log, trace, journal, report, exception, `toString` or JSON | `assertNoPayloadLeak` in every layer |
| A7 | Each provider writes its documented shape, and refuses what it cannot take **before** any HTTP call | R10, R11 tests, contract cases |
| A8 | Text-only requests are byte-identical to before the change for every provider | golden bodies |
| A9 | Decorators keep media or refuse; PII `mask` or `block` refuses media unless opted in | R12, R13 tests |
| A10 | A media-heavy request cannot slip past a budget or a context window because of an under-estimate | R14 tests |
| A11 | `attach` parses on every statement in the list from P4 and is rejected, with a clear message, elsewhere; existing scripts are unchanged | parser tests, `RepositoryScriptsTest` |
| A12 | `weave check` finds each R17 case before a run | check tests |
| A13 | A path outside `media_root` cannot be read by any route (traversal, symlink, substitution, model output) | R16 tests and guard-removal checks |
| A14 | A run with media journals descriptors only, resumes without repeating an effect, and fails if the file changed | R20 tests |
| A15 | `weave audit` treats media from a variable as untrusted content and is listed in the docs | R21 tests, `SecurityDocTest` |
| A16 | The graph, Fabric page, VS Code grammar and snippets know the feature | R22, R23 tests |
| A17 | Every page that mentions messages, agents, the language or providers is correct, and every sample works | §3 and the docs tests |
| A18 | No new runtime dependency in a published module; `mvn dependency:analyze` shows no unused one | §2 |
| A19 | Mutation score on the new profile is at least 85% and the existing profiles did not drop | §2 |

## 2. Quality gates

- `mvn verify` green on `ai-agent4j`, `ai-agent4j-tools` where touched, `ai-agent4j-loom`, `loom-inspect*`, `loom-fabric`, `loom-maven-plugin`, `ctk`, the Spring starter; coverage gates not lowered.
- `make format-check`, `make release-check` (javadoc on every public type and method).
- After a change to a Loom class used by `loom-inspect*` and `loom-fabric`: `mvn -pl src/loom/ai-agent4j-loom -DskipTests install` first.
- `bash scripts/check-graph-render-sync.sh` after the graph change; `GuideTutorialPageTest` and `StartersTest` if their markdown or `main.loom` files changed.
- `mvn dependency:tree` for `ai-agent4j` before and after: no change expected.
- GitGuardian clean; no credential anywhere.

## 3. Documentation checklist (principle 7)

Grep the whole repository for `Message`, `delegate`, `attach`, `human_prompt`, `LOOM_PROMPT`, "provider" and "multimodal", and check each hit. At least:
`README.md`; `src/ai-agent4j/wiki` (messages, the uniform contract, each provider page, a new media page linked from `Home.md`); `wiki/`; `docs/guide/06-build-the-workflow.md` and the tutorial chapters where the clause is relevant;
`LOOM_GUIDE.md` and `LOOM_PROMPT.md`; `docs/security/weave-audit.md` and `owasp-llm-top-10.md`; `docs/API_COMPATIBILITY.md`; `docs/VERSION_MATRIX.md` if it lists capabilities; `docs/RELEASE_NOTES_<version>.md`;
`llms.txt`; the skills (`llm4j-workflow-guide` and its references, `llm4j-loom-deployer` for the run page); the VS Code extension README, grammar, snippets and `CHANGELOG.md`; the CTK scenarios.

## 4. Manual checks

| # | Check | Done when |
|---|---|---|
| M1 | Run a real Loom script that asks a vision model about a screenshot, on each provider that supports images, with the owner's keys | the answer is about the image; the journal holds no bytes |
| M2 | The same with an audio file on Gemini, and a short video on Gemini | the answer is about the media |
| M3 | Point an Anthropic agent at a video | `weave check` says so before the run, and the run refuses before any call |
| M4 | Try a path outside the root, a symlink out, and a script that builds a path from a model's answer | all three are refused |
| M5 | `pii: mask` agent with an attachment | refused, with the message naming the setting; opt-in then works |
| M6 | Watch a media run in the inspect page and in Fabric | descriptors shown, no bytes |
| M7 | Cache check: a ten-turn Anthropic loop with and without caching | the cached count rises and the cost falls; recorded with the figures |

## 5. Exit checklist

- [ ] All of A1 to A19 pass with the evidence named.
- [ ] Guard-removal checks (TEST_STRATEGY §5) done and recorded.
- [ ] Documentation checklist (§3) done; samples verified.
- [ ] Recorded fixtures cover every provider cell marked yes in DESIGN §4.3; unconfirmed cells are marked so.
- [ ] Release notes and `API_COMPATIBILITY.md` updated.
- [ ] The roadmap row is current.
- [ ] Anything not done is listed in §7 under "Left out", with the reason.

## 6. Risks

| Risk | Mitigation |
|---|---|
| The §4.3 table is wrong for a vendor or a model | P2 comes first; capabilities live in data, not code; a wrong cell fails closed (a refusal), not open |
| Token estimates under-count and a budget is breached | conservative constants, vendor usage replaces them, a test compares with recorded usage |
| Media in the history makes a long loop expensive | R19 compaction drops old media and says so; budgets include the estimate |
| Prompt injection through an image or a recording | R21 audit rule; media from a variable counts as untrusted; the text guards cannot see it, which the docs state plainly |
| A Loom change breaks existing scripts | keywords only in new positions; `RepositoryScriptsTest`, the guide parse tests and the CTK |
| Scope is large | six sub-phases, each green and pushed alone; 31e's `human_prompt` display may slip without blocking the rest |

## 7. Results

### 31c: the providers (built; live-confirmed for the providers whose keys were available)

**Built.** Per-model media capabilities for the OpenAI family (`Dialect`, `ResponsesDialect`, the patterns in `MediaModels`), Anthropic, Google and Ollama; the writers for Anthropic (`image`, `document`), OpenAI chat (`image_url`, `file`, `input_audio`), OpenAI Responses (`input_image`, `input_file`), Google (`inline_data`) and Ollama (`images`); `Providers.requireMedia`, called first by every provider that takes media and by Sarvam, so media is refused and never dropped even when a provider is called directly; Google's 20 MiB inline limit (R11, R10, R3). The user page [Media-Input.md](../../../src/ai-agent4j/wiki/Media-Input.md) with a docs-example test (`MediaDocsExamplesTest`), linked from the wiki index and `llms.txt`; `API_COMPATIBILITY.md`; the release notes.

**Live confirmation (P2 and P3), 2026-11-06.** By hand, then through the library with `mvn -Plive` (`LiveMediaTest`, `LiveCachingTest`: 13 tests, 0 failures, 0 skipped, a 60,000-token budget per provider):

| Check | Result |
|---|---|
| Anthropic `claude-haiku-5-5`: picture | "Red" |
| Anthropic: PDF containing `TOTAL 4217` | "4217" |
| Anthropic: `audio` and `video` blocks | HTTP 400 "Input tag 'audio' found using 'type' does not match any of the expected tags" (so refusing both is right) |
| Anthropic: a marked 13,003-token system block, sent twice | call 1 wrote 13,003 tokens to the cache, call 2 read the same 13,003 (**closes M7**; through the library: the second call read over 1,000 tokens the first had written) |
| Anthropic: five `cache_control` markers | HTTP 400 "A maximum of 4 blocks with cache_control may be provided. Found 5." (**confirms the limit of four**) |
| OpenAI chat `gpt-5-mini`: picture, PDF as a `file` part | answered (the picture: "Maroon" for a pure red picture on the hand call, "red" when the question named the choices) / "4217" |
| OpenAI Responses `gpt-5-mini`: picture, PDF as `input_file` | "Red" / "4217" |
| OpenAI `gpt-audio` and `gpt-audio-mini`: `input_audio` WAV | HTTP 200 (the two models disagreed whether a tone is speech: the wire shape is confirmed, their judgement is not) |
| OpenAI `gpt-5-mini`: `input_audio` | HTTP 400 (so sound is per model, not per provider) |
| xAI `grok-4.20-0309-non-reasoning`: picture | "red" |
| Google `gemini-2.5-flash`: picture, PDF, WAV, MP3, MP4 (all `inline_data`) | "Red", "4217", "Tone", "Tone", "Red." |

The sniffer's rules also match the first bytes of real files made with ffmpeg (WAV `RIFF…WAVE`, MP3 `ID3`, MP4 `ftypisom`) and of a real PNG and PDF, so the synthetic test fixtures were faithful.

**Tests (written first, red because `mediaCapabilities` did not exist, then green).** `MediaModelsTest` (a table over every provider and model named in DESIGN §4.3), `MediaBodyTest` for Anthropic, OpenAI (chat and Responses), Google and Ollama, `MediaRefusalTest` for Sarvam, `MediaDocsExamplesTest`, plus the tagged live tests.

**Guard-removal checks (each guard disabled by hand, a test failed, then restored).** The shared provider refusal (`requireMedia`); Google's 20 MiB inline limit; the MP3-to-`mp3` mapping; the refusal of audio formats the API does not take; audio models not being offered pictures; `o1-mini` and `o3-mini` not being offered pictures; Anthropic needing a Claude model; Gemini speech models not being offered media. **One was missed on the first pass** (Anthropic needing a Claude model: no test gave Anthropic another model name) and one "catch" was not valid (my edit left the file uncompilable). A test was added and the check redone with a well-formed edit; all are now caught.

**Deviations from the design.**
- The OpenAI Responses API is offered pictures and PDFs only. Sound there is not offered, though it was not tried against the real API; chat completions is the confirmed route.
- OpenAI's audio models are not offered pictures or PDFs (their documentation lists text and audio), and a plain chat model is not offered sound. Pictures on `gpt-4-turbo` and older are not offered either: the patterns record only what is known, so the older models are refused until someone adds them.
- Kimi and Qwen are offered pictures on models whose names say vision (`vision`, `-vl`). **Not confirmed**: no key. Ollama is offered pictures on models whose names match the pattern in `OllamaProvider`; **not confirmed**: no running server. Both are tested against the request they must send.
- A generic OpenAI-style server takes no media. A user running a local vision model on LM Studio or vLLM is refused until the dialect can be told (`Dialect.Builder.mediaModels`, public, takes a pattern); a setting for it in `LLMConfig` is **not built**.
- `Media.fromFile` still takes any path (path containment is Loom's, 31e).

**Not verified (owed).** Kimi, Qwen and Ollama against the real services. Video on any provider but Gemini (none of the others takes it). PIT mutation profile for the media classes (TEST_STRATEGY: 85%). The downstream suites for this commit (see the end of this section).

### 31b: the media model and guards (built; wider module checks owed, see the end of this section)

**Built.** `MediaKind`, `Media`, `MediaDescriptor`, `MediaPolicy`, `MediaCapabilities` and the package-private `MediaSniffer` (R7, R8, R9, R15); `Message.media` with its Jackson behaviour (R7, R9); `LLMRequest.mediaPolicy`, `mediaKinds()` and the per-message and per-request limits (R8); `mediaCapabilities(model)` on `LLMClient` and `LLMProvider` (default `NONE`), the refusal in `DefaultLLMClient.chat` and `chatStream`, and the pass-through in the budgeted, masking and routing clients (R10, R12 in part).

**Tests (written first, red because the types did not exist, then green).** `MediaTest` (39, counting the table cases), `MediaPolicyTest` (6), `MessageMediaTest` (10), `LLMRequestMediaTest` (8), `MediaCapabilitiesTest` (9), `MediaClientGuardTest` (9). `mvn verify` on `ai-agent4j`: 3,628 tests, 0 failures, all coverage checks met; javadoc builds.

**Guard-removal checks (each guard disabled by hand, a test failed, then restored).** The kind-versus-bytes check, the per-item size check, the policy allow-list check, the empty check, user-only media, the per-message count, the per-request bytes, the request-level item size, the client's capability refusal, and the PDF sniff. **Two were missed on the first pass** (the kind check and the empty check): another guard behind each refused the same input with a worse message, and the tests only asserted the exception type. The tests now pin the message, and both are caught.

**Deviations from the design.**
- Formats beyond the spec's list are sniffed: FLAC, Ogg audio and QuickTime video. Each is in the default allow-list; remove them with `MediaPolicy.builder().allow(...)` if not wanted. The §4.3 provider table decides what is actually sent, in 31c.
- `MediaPolicy` is a record with a builder, and `allow(...)` refuses a media type the sniffer cannot produce, so a policy can never look like permission it cannot grant.
- `Message` JSON drops `media` on reading (the descriptor cannot rebuild the bytes), as DESIGN R9 says a replay reloads them; the Jackson `@JsonIgnoreProperties` is limited to that one property.
- A file is read with a bounded read of one byte past the limit and not by trusting its size on disk; a directory, a device or a missing file is refused. **Path containment (R16) is Loom's job in 31e**, not this class's: `Media.fromFile` takes any path the caller gives it, so a caller that takes a path from outside must check it first.
- The sniffer looks at the leading bytes only (a file that starts like a picture and is something else later still passes; decoding is the provider's job). The javadoc says so.
- No wiki page yet: a page about attaching media would describe something no provider can send. It arrives in 31c with the first writer, together with the §3 documentation checklist for the model.

**Not verified (owed).** The full test suites of `eval4j`, `ai-agent4j-tools`, `ai-agent4j-addons` and Loom against the changed `Message`/`LLMRequest`/`LLMClient` (run at the end of this section); a mutation profile for `Media`, `MediaPolicy`, `MediaSniffer` and the capability check (TEST_STRATEGY: 85%); `assertNoPayloadLeak` for logs and the journal (nothing logs or journals media yet, so this lands with 31c and 31e).

### 31a: prompt caching (built; Loom module check owed, see the end of this section)

**Built.** `PromptCache` and `LLMRequest.promptCache` (R1); the Anthropic writer marks the system block, the last tool and the named messages, and refuses an index past the end before any call (R1); `usage.cached_tokens` on Anthropic and Google, `usage.cache_write_tokens` on Anthropic, in the whole response and on the last chunk of a stream, with OpenAI's key moved to one shared constant (R3); `PriceTable.Price` cache read and write rates, the price file format, `BudgetSet.Pricing.cost(...)` and the budgeted client's settlement (R4); `ReActAgent.promptCaching` and its default (R5); the Loom agent setting `cache: true | false` with its parser, executor wiring, hover, highlighting, guide, prompt, `llms.txt`, wiki pages, `API_COMPATIBILITY.md`, release notes and extension changelog (R5, D6).

**Tests (written first, red for the right reason, then green).** `PromptCacheTest` (8), `PromptCacheBodyTest` (10), `CachedUsageTest` (5), `CachePricingTest` (9), `ReActAgentPromptCacheTest` (3), `AgentPromptCacheTest` (6, Loom). `mvn verify` on `ai-agent4j`: 3,547 tests, 0 failures, 2 skipped (the existing ones), all coverage checks met.

**Guard-removal checks (done by hand, each failed a test, then restored).** The index range check (`anIndexBeyondTheMessagesIsRefusedBeforeTheCall`); the rule that a marked text block cannot collapse to a plain string (`aMarkedMessageEndsInAMarkedBlock…`, `allFourBreakpointsAreWritten`); the tool-list marker (`onlyTheLastToolIsMarked`, `allFourBreakpointsAreWritten`).

**Deviations from the design.**
- R4: "the trace says so once" for a model with no cache rate was **not built**. A model without cache rates is priced as ordinary input, which charges a write right and a read too much; the page says so. No flag is added to the response.
- The Loom syntax is `cache: false` (true or false, like `streaming`, `reasoning` and `steerable`), changed from the `on | off` first proposed in D6 on the owner's decision; the setting shares the parser branch of those three.
- `cache` is part of the agent's *behaviour on the bill*, not its answers, so it is **not** in `AgentIdentity` (the eval fingerprint). Changing it does not invalidate a cached eval result.
- Only the native tool-calling loop sends the cache. The text-format loop (a model that cannot call tools), the wrap-up call and the other `LLMRequest`s `ReActAgent` builds do not. The Spring properties do not take cache rates.
- `weave check`, `weave audit` and the graph have nothing to say about `cache`, by design (it moves no data).
- One existing test, `SearchBudgetTest.thePriceTableReadsAnOptionalPricePerThousandSearches`, asserted that a four-field price line is an error. That is exactly what R4 makes valid (the fourth field is the cache read rate), so its invalid example became a six-field line. The behaviour it protects, that a malformed line is refused, is unchanged.

**Not verified (owed).**
- **P3, the vendor facts**: the four-breakpoint limit, the minimum cacheable length (below it Anthropic silently does not cache), the TTL and the write premium are from my knowledge of the API and the request shape is tested against the code's own expectation, not a recorded live response. The first live call (M7) confirms it.
- **M7**: the ten-turn Anthropic loop with and without caching, with figures.
- A recorded Anthropic cache response fixture: `CachedUsageTest` uses hand-written bodies that follow the documented field names.
- PIT mutation run for the changed classes (none of them is in a mutation profile yet).

