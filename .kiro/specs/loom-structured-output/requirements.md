# Requirements Document

## Introduction

`output_schema` (and a step's `expecting`) promise that an agent's answer is a structured value. Until now nothing checked the promise. The delegate loop asked the model for JSON, tried to read it, and on any failure passed the **raw text** on with a log line. A later step then failed far from the cause, or carried on with nothing.

It happened on the first real run of the Nirmaan Yantra software factory: Gemini answered a `files:` schema in YAML (with `content: |` block scalars) instead of JSON. The 21,400 characters of tests it wrote were fine; Loom could not read them; `SaveFiles` was given an empty `e2e_out.files` and refused to run. `retry 2` could not help, because the step had "succeeded". The same code also threw a string-index error on an answer whose code fence was never closed (`Range [7, 0) out of bounds`), which is how Rishi's first, truncated spec failed.

This spec makes the promise real: **an answer that is not what the schema promised is a failed attempt**, and the agent is told what was wrong.

Standing rules: nothing silently ignored; existing valid scripts keep working (see R5); logic lives in the framework.

**Out of scope:** the provider's own JSON mode (Gemini `responseMimeType` + `responseSchema`). It cannot be combined with function calling, which most agents here use, and needs a live check; it is a follow-up that makes the model unable to answer in YAML, where this spec makes a wrong answer cost one correction.

## Requirements

### Requirement 1: Finding the answer
1. The JSON may be inside a code fence (closed or not) or inside a sentence of chatter: the first complete object (for a list schema, list) is used. Brackets inside strings and escaped quotes do not confuse the scan.
2. When there is none, or it is cut off, the problem says which ("the answer is not JSON: it starts ...", "the JSON is not finished (the answer may have been cut off at the output limit)").

### Requirement 2: Checking it
1. Every field the schema names must be present and not null; a missing field is reported as `path: missing`.
2. Types: text (a number or true/false is accepted as text; an object or list is not), number, true/false, list (each element checked, path `items.1.n`), nested object. An enum must be exactly one of its words (reported with the allowed words). Fields the schema does not name are ignored.
3. At most five problems are named; the rest are counted.

### Requirement 3: What a violation does
1. It is a failed attempt of the delegate: it is traced (`schema_violation` with `attempt` and `problems`), reported to `onDelegateError`, and retried.
2. One correction is always allowed, whatever `retry` says. On the retry the task is followed by `Your previous reply was rejected: <problems>. Reply again with ONE JSON object only ...`.
3. When attempts run out the step fails: `on_failure` runs with `{_error}` naming the problems. The raw text is never bound to the variable.
4. A violation is never retried by the budget machinery (a budget stop is unaffected), and a step that ran out of budget part-way keeps its best answer unchecked, as before.

### Requirement 4: Applies everywhere a schema does
`output_schema` and `expecting` alike; every provider; `weave eval --mock` is unaffected (the mock produces schema-valid values).

### Requirement 5: Compatibility
1. A delegate the runtime builds itself and checks on its own terms is exempt: the decision machinery's proposal (where a missing `confidence` is allowed and a missing choice sends its own reminder) reads the answer leniently, as before.
2. The documented contract (Loom guide, "Output Schema Enforcement") says what is checked.
3. A script that relied on a raw string reaching it will now see the step retried and then fail. That is the point; there is no switch to turn it off.
