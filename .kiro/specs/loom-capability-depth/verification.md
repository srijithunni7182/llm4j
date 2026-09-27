# Verification Plan

The work is done when every check below passes, in automated tests unless marked *live*.

Unless a check says otherwise, it uses:

| Stand-in for | What |
|---|---|
| Models | scripted mock `LLMClient`s that record requests |
| Embeddings | `HashingEmbeddingProvider` |
| HTTP services | MockWebServer |
| Files | JUnit `@TempDir` |

## V1: Agent memory (R1)

| # | Check |
|---|---|
| V1.1 | The full `memory { … }` block parses, with and without `:`. `type:`/`path:` give a load error naming the new keys. `limit: 0`, `recall: -1` and `min_similarity: 2` are errors. A block with neither `conversation` nor `facts` is an error. |
| V1.2 | Conversation persists across executors. Run 1 delegates "My name is Asha" (session `u1`). A new executor on the same directory store delegates again: the request the model receives contains "My name is Asha". |
| V1.3 | Sessions are separate. `session: "{user}"`: a delegate with `user=u2` does not see `u1`'s messages. |
| V1.4 | `limit: 2`: only the last 2 messages are in the prompt. |
| V1.5 | Facts. The model calls `save_memory_fact` with a fact. A new executor on the same facts file, asked a related question, receives "Relevant context from user's long-term memory" with the fact. A different session does not. |
| V1.6 | Replay: a resumed run whose step is journaled makes no model call and appends nothing to the store (the file is unchanged). |
| V1.7 | `memory_recalled` audit event, and a `memory` trace event. |
| V1.8 | Facts without `embedding`, or with an unknown embedding provider: load error. |

## V2: Voice and languages (R2)

| # | Check |
|---|---|
| V2.1 | `tools: [translate]` with no `SARVAM_API_KEY`: load error naming the tool and the variable. With the key set, it validates. |
| V2.2 | `translate`, `transliterate` and `detect_language` post to `/translate`, `/transliterate` and `/text-lid` on the mock, with the `api-subscription-key` header and the expected JSON fields, and return the mock's text. |
| V2.3 | `speak` writes a decoded WAV under `out` and returns its path. `transcribe` uploads a file and returns the transcript. |
| V2.4 | `transcribe` with `path: "../secret.txt"` or an absolute path outside the directory is refused, and no request is made. `speak` with `out: "/tmp/x"` is refused at load. |
| V2.5 | `voice { listen }`: a delegate whose payload is `clips/q.wav` sends the transcript (not the path) to the model. |
| V2.6 | `voice { speak }`: after a delegate, `{answer_audio}` holds a path to an existing WAV. On replay, the value is restored with no TTS call. |
| V2.7 | A TTS failure (mock 500) runs `on_failure` with `_error`. `voice { speak: "other/x" }` gives a load error. |
| V2.8 | Library unit tests: `/text-lid`, the transcription model, the Sarvam chat default model, and that `toBuilder` keeps the TTS language and model. |

## V3: Providers (R3)

| # | Check |
|---|---|
| V3.1 | `model: "sarvam/sarvam-m"` with the key and `SARVAM_BASE_URL` pointed at the mock: an agent run posts to `/v1/chat/completions` with `"model":"sarvam-m"`. |
| V3.2 | `provider Box { use: ollama base_url: "http://…mock" }` with `model: "Box/llama3"`: the request goes to the mock with model `llama3`. |
| V3.3 | `provider P { use: gemini api_key: "literal" }`: load error (secret must be env). A missing variable, an unknown `use`, a name `gemini`, or a duplicate: each is a load error. |
| V3.4 | `model: "gpt-5"` with the default factory: load error. With a mock factory, it loads (the host is trusted). |
| V3.5 | Routing primary and fallbacks can use `Name/model`. |

## V4: Knowledge graphs (R4)

| # | Check |
|---|---|
| V4.1 | `add`, then `query` through the tool named as declared. |
| V4.2 | A file store survives a new executor. Two declarations with the same file share one graph. |
| V4.3 | `read_only: true` refuses `add`. A corrupt file gives a load error with the line. |
| V4.4 | Library: `FileGraphStore` round trip. |

## V5: Guards (R5)

| # | Check |
|---|---|
| V5.1 | `pii: mask`: a task containing an email and a phone number reaches the model with placeholders only. That holds for every recorded request, including ones after a tool observation that contains PII. The stored answer is masked. `pii_masked` is audited. |
| V5.2 | `pii: block`: PII in the task means no model call, `on_failure` runs, and `_error` lists the types but not the values. PII in the answer means the variable is not set and the step fails. |
| V5.3 | `pii: warn`: `pii_detected` is audited and the value is stored unchanged. |
| V5.4 | `bias: warn`: an answer with "women are bad at math" gives `bias_detected`, and the value is stored. `bias: block` fails the step. A neutral answer passes. |
| V5.5 | `bias_model`: the judge's JSON findings are used, and its calls count against the run budget. Unparseable judge output means no findings. |
| V5.6 | Replayed steps are not re-checked. Bad keys or values in `guard` give a load error. |
| V5.7 | Library: `MaskingLLMClient`, `RuleBasedBiasMonitor` (positive and negative cases), `LLMBiasMonitor`. |

## V6: Remote skills and discovery (R6)

| # | Check |
|---|---|
| V6.1 | An `http://localhost:<mock>/skill.md` skill appears in the agent's system prompt. A 404 gives a load error. `http://example.com/x.md` gives a load error (HTTPS required). |
| V6.2 | A `skill_registry` tool searches and reads from the mock registry. |

## V7: Personas (R7)

| # | Check |
|---|---|
| V7.1 | A script persona appears in the prompt, followed by `system:`. |
| V7.2 | A `PersonaLibrary` name still works. An unknown persona gives a load error. A persona without `role` gives a load error. |
| V7.3 | `system_template` without a registry, or with an unknown id: load error. With a registry and a known id: it is used. |

## V8: Trace (R8)

| # | Check |
|---|---|
| V8.1 | A listener receives, in order, `delegate_start`, `thought`, `action`, `observation`, `delegate_end` for a tool-using agent, each with the agent and step. |
| V8.2 | `weave run --trace` prints lines like `[main/s0] Researcher  🔧 …` to stderr. `--trace=json` prints parseable JSON lines. Without the flag there is no trace output. |
| V8.3 | Approval, guard and memory events appear when those features are used. |

## N: Non-functional

| # | Check |
|---|---|
| N1 | All module suites are green: ai-agent4j, addons, eval4j, Loom, Engram, GetViral. |
| N2 | Every `.loom` file in the repository passes the `RepositoryScriptsTest` sweep. |
| N3 | The guide's new examples parse (`DocumentedExamplesTest`). |
| N4 | No new runtime dependencies. |
| N5 | New Loom and library classes have ≥ 85% line coverage (JaCoCo). |
| N6 | `weave check` makes no model or embedding call. Only remote skills are fetched. |

## L: Live (optional, needs keys)

| # | Check |
|---|---|
| L1 | Sarvam: translate English to Hindi, detect language, speak a short text, then transcribe it back. |
| L2 | `sarvam/sarvam-m` answers a question. |
| L3 | Gemini-embedded facts recalled across two runs. |
