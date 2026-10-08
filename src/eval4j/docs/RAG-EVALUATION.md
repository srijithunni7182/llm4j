# RAG evaluation

Evaluate the retriever, not just the generator: contextual precision, recall and relevancy.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Contextual RAG judging

Evaluate the retriever, not just the generator. Each metric judges per chunk (relevant = rating 4+):

```java
LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

assertThat(ragResult)   // the object under test isn't consulted; the context is what's graded
    .is(presets.contextualPrecision(question, expectedAnswer, retrievedChunks))  // ranking quality
    .is(presets.contextualRecall(question, expectedAnswer, retrievedChunks))     // is the answer in the context?
    .is(presets.contextualRelevancy(question, retrievedChunks));                 // share of useful chunks

// No judge LLM: cosine similarity via any EmbeddingProvider (e.g. ai-agent4j-addons ONNX/DJL)
.is(EmbeddingRelevance.using(embeddingProvider).contextualRelevancy(question, chunks, 0.6))
```
