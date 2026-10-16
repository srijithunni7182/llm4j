# Design Document

## Overview

One new class, `StructuredAnswer` (package `io.github.llm4j.loom.execution`), and about 25 lines in `HarnessExecutor.executeDelegate`. The delegate loop already retried on any `Exception` (honouring `retry N`, backoff, the step's `on_failure` and the `onDelegateError` hook); the only thing missing was a throw.

```
answer text ──extract(schema)──▶ JSON text ──Jackson──▶ value ──problems(value, schema)──▶ [] ok | [..] SchemaViolation
```

## `StructuredAnswer`

- `extract(raw, schema)`: strips a fence (an unclosed one too); then, for an object or list schema, scans from the first `{` / `[` counting every opening and closing bracket outside strings (handling `\` escapes) and returns the first balanced span. Nothing found: `SchemaViolation("the answer is not JSON: ...")`; never balanced: `SchemaViolation("the JSON is not finished ...")`.
- `problems(value, schema)`: walks the `SchemaDef` (OBJECT fields, LIST element type, ENUM words, STRING, NUMBER, BOOLEAN) and returns `path: problem` strings. The root path is `answer` and is left out of field names (`advice: missing`, `items.1.n: expected a number`).
- `parse(raw, schema)`: both, returning the value or throwing `SchemaViolation` (a `RuntimeException`, so the existing loop catches it).
- `parseOrRaw(raw, schema)`: the tolerant extraction with no checking, falling back to the text. Used only by delegates marked `lenientSchema`.
- `SchemaViolation.correction()`: the text appended to the task for the next attempt.

## The delegate loop

```java
if (schema != null && partial == null)
    finalValue = del.isLenientSchema() ? StructuredAnswer.parseOrRaw(answer, schema) : StructuredAnswer.parse(answer, schema);
...
} catch (StructuredAnswer.SchemaViolation violation) {
    trace(SCHEMA_VIOLATION, ...); onDelegateError(...); lastError = violation; attempts++;
    if (!corrected) { corrected = true; maxAttempts++; }          // one correction, whatever `retry` says
    contextBriefing += violation.correction();
}
```

`DelegateStmt.lenientSchema` is false for every statement the parser builds; only `Decider` sets it, on the synthetic delegate that asks an agent for a proposal.

## Why not the provider's JSON mode here

Gemini rejects `responseMimeType: application/json` together with function declarations (to be confirmed with a live call), and Rishi and Vihaan have `web_search`. The check works for every provider and every agent; native JSON mode can be added later for tool-less agents (Drishti, the one that failed) to prevent the mistake instead of correcting it.

## Found while doing it (fixed)

`Decider` proposals broke under strict checking (six tests): it validates its own answer and allows a missing `confidence`. Fixed with `lenientSchema`, not by loosening the rule for everyone.
