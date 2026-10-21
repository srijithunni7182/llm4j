# Release notes: 6.1.0 (in preparation)

This page is written as the release is built. Each item says whether it **works now** on the release branch or is **coming**; nothing
that is not finished is described as working. The API policy is in [API_COMPATIBILITY.md](API_COMPATIBILITY.md).

## Classification: typed questions, typed answers

Two jobs in an agent system need an answer from a fixed set: routing a message (what does the customer want?) and judging an output
(did it meet the criterion?). Both used to mean asking a chat model for text and parsing it. 6.1.0 adds a `Classifier`: you ask typed
questions (a choice from your labels, a yes/no, a point on a scale) and get typed answers, from any chat model, with a result that can
never contain a label you did not offer. Design: [specs/07-classifier](specs/07-classifier/DESIGN.md).

| Piece | Status |
|---|---|
| `Classifier`, `ClassifyRequest`, `LlmClassifier` (any chat model), `ClassificationProvider` SPI, `ScriptedClassifier` test double (eval4j). Wiki: [Classification](../src/ai-agent4j/wiki/Classification.md) | **works now** |
| `llmJudged(...).classifier(...)` and `ClassifierComparison` (compare backends on your own labelled cases) in eval4j. Guide: [LLM-as-judge](../src/eval4j/docs/LLM-AS-JUDGE.md#judge-with-a-classifier) | **works now** |
| `JevProvider` (TypeSafe AI's Jev) and the `jev` provider name | coming |
| Loom `intentclassifier` / `intentclassify`, with `weave check` and `weave audit` rules | coming |
| VS Code support for the new Loom syntax | coming (may follow in 6.1.x) |

### What Jev is, and what we found

Jev is a *decision model* from TypeSafe AI: it answers typed questions directly, in one call, with probabilities, and does not write text. We
measured it against Claude Opus 5.5 and Claude Haiku 5.5 on labelled data before building anything ([the study](specs/07-classifier/STUDY_REPORT.md)):

- **Intent routing and judging: recommended.** Same accuracy as the chat models on our sets, better calibrated, about 3 to 9 times lower p95 latency (about 170 ms
  median) and 2 to 4 times cheaper than the cheap chat model (about 100 times cheaper than the strong one), and the same answer on every repeat.
- **As the only safety guardrail for shell commands: not recommended.** It was below the cheap chat model on both sets and once called `> /etc/passwd` safe with confidence 0.95.
- The sets are small (30 to 65 cases per task) and ours; differences of one or two cases are noise. Rerun the comparison on your own cases before relying on it.
