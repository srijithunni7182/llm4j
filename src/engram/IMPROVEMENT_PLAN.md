# Engram: review findings and improvement plan

Status: **to do**. This plan comes from a review of Engram's main claim: agents don't need the whole
transcript, so Engram saves tokens. Pick it up in a fresh session; everything needed is here.

## 1. Where the claim stands today

**Evidence in the repo is weak:**

- `engram-core/benchmark-results.md` shows *both* modes crashed, with 0 tokens and 0 calls. The README
  still says "Transcript crashed, Engram completed the build".
- The 76% / 89% savings tables (`README.md`, `comparative_benchmark_study.md`, `simulation-results.md`)
  come from `TokenSimulationTest`. It is arithmetic on fixed assumptions (the briefing grows by 30 tokens
  a turn, upkeep is a flat 400 tokens a turn) and never runs Engram.

**Measured behaviour.** `ContextCostBenchmarkTest` runs the real engine: embeddings, retrieval scoring
and the CIA prompts. The model is a token-counting stub. The scenario is 5 agents with 600-token outputs,
and every turn needs a decision made in an earlier turn. To reproduce:

```bash
cd src/engram/engram-core
mvn -q -o test -Dtest=ContextCostBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
cat target/context-cost-benchmark.md
```

The table gives total tokens. The "fact reached" figures count how often the fact a turn needed actually
appeared in the agent's prompt.

| Turns | Loom default (shared transcript) | Engram, LLM briefing | Engram, template | Facts passed as `{variables}` |
|---|---|---|---|---|
| 10 | 37.8k | 36.2k (39 calls vs 10) | 37.8k | 8.6k |
| 20 | 140k | 79k, fact reached 18/19 | 140k | 17k |
| 50 | 837k | 212k, fact reached 38/49 | 837k | 43k |
| 50, billed with prompt caching¹ | 251k | 203k | 837k | 43k |

¹ The cached prefix is billed at 10% of the normal input price. A transcript that only grows at the end
caches well. Engram's briefing is rewritten every turn, so it barely caches.

**What the numbers say:**

1. **Each agent's prompt stays small and flat.** At turn 50 it is about 560 tokens, against 32k for the
   transcript. This part of the claim is true, and it matters for small local models and context limits.
2. **Upkeep eats most of the saving.** Every turn makes 3 extra model calls: extract, introspect and
   synthesize. Extraction and introspection each re-send the agent's full output. Upkeep comes to about
   2.5× the agents' own tokens, and break-even is around turn 10, not turn 2 as the README claims.
3. **With prompt caching** the advantage shrinks to parity at 20 turns and about 20% at 50 turns.
4. **Recall degrades.** The summary under 200 words drops facts: at 50 turns, 22% of the facts a turn
   needed never reached the agent.
5. **Template mode saves nothing.** It is the default, `new EngramEngine()`. It stores whole outputs,
   `MAX_CANDIDATES = 50`, and `MIN_SCORE = 0.10` lets almost everything through, so it amounts to a
   reordered transcript that can't be cached.
6. **Loom's native style wins by about 5×.** Passing earlier results into the task as `{variables}` gives
   100% recall. The cost is that the script's author has to know which results each step needs.

**Where Engram helps today.** In GetViral it is not a transcript replacement. Only 2 agents recall from
it, and everything else is passed explicitly. Its value there is **memory across runs** (what worked for
this creator) and **novelty checks** (`nearest` / `novelty`), neither of which a transcript can do.

## 2. Changes to make

Code lives in `engram-core/src/main/java/io/github/llm4j/engram/core/`.

### A. Cut upkeep: fewer calls, no repeated output
- [ ] Merge `extractMemories` and `introspect` into **one** CIA call per outcome (for example, one prompt
      that returns both `[FACT]` and `[INSIGHT]` lines). The agent's output is then sent once, not twice.
      Keep the `ContextIntelligenceAgent` interface source-compatible: add a default combined method and
      have `EngramEngine.storeOutcome` prefer it.
- [ ] Let the CIA use a separate, cheaper model. GetViral's `GetViralEngine.intelligence()` would pick
      it up.
- [ ] Skip introspection when the result is trivially short, or add a flag to turn it off.

### B. Stop summarizing and keep the facts
- [ ] Replace the LLM `synthesizeBriefing` call with a **budgeted top-k**: put the highest-ranked extracted
      facts into the prompt verbatim, up to a token budget (default about 8 facts or 400 tokens). This
      removes a model call and the recall loss that summarizing causes.
- [ ] Keep a summarizing mode only as an opt-in (for example, when the candidates exceed the budget by a
      wide margin).
- [ ] Put the stable part first (task, then facts in a deterministic order) so providers can cache more of
      the prefix.

### C. Fix template mode
- [ ] `TemplateContextIntelligenceAgent.extractMemories` should store a short fact, not
      `"Agent X completed task [...] with outcome: <full output>"`. Start with the first sentences or the
      lines that look like decisions, capped in length.
- [ ] Tune `MAX_CANDIDATES` and `MIN_SCORE` (or turn them into a budget) so the template briefing is
      actually selective. Make them constructor options.

### D. Honest docs
- [ ] Replace the tables in `engram-core/README.md`, `comparative_benchmark_study.md` and
      `simulation-results.md` with output from `ContextCostBenchmarkTest`, including the prompt-caching
      column and recall.
- [ ] Delete or regenerate `benchmark-results.md`. It currently shows a double crash.
- [ ] Retire `TokenSimulationTest`, or label it clearly as a projection.
- [ ] Reposition the pitch. Lead with **long-term memory per user or agent, plus novelty checks**, and
      present bounded context as a benefit for small models and long sessions. Only claim token savings
      at the measured break-even point.
- [ ] Update `docs/AGENTIC_WORKFLOWS_GUIDE.md` and the Engram bullets in the root `README.md` to match.

### E. Measure again
- [ ] Extend `ContextCostBenchmarkTest` with the new modes, before and after the changes. Targets: 50-turn
      recall of at least 95%, upkeep of at most 1× the agents' tokens, and break-even at or before turn 5.
- [ ] Optional: one run against a real model (Gemini or Ollama) to confirm what the stub assumes about
      output sizes. The stub returns about 4 facts per extraction, a summary of 200 words or less, and a
      one-line introspection.
- [ ] Run GetViral's suite (`src/examples/getviral`, `mvn install`) because it uses `CreatorMemory`, which is
      built on `EngramEngine`.

## 3. Things to keep as they are
- `nearest` / `novelty` / `remember`: plain cosine similarity, used by GetViral's originality gate.
- `VectorStore` plug-ins (`InMemoryStore`, `PGVectorStore`).
- The `MemoryEngine` contract with Loom (`assembleContext` / `storeOutcome`).
