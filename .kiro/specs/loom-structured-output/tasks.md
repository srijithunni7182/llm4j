# Implementation Plan

- [x] 1. `StructuredAnswer`: extraction, checking, correction text.
- [x] 2. `DelegateStmt.lenientSchema`; `Decider` sets it.
- [x] 3. Delegate loop: strict parse, `SchemaViolation` catch, one free correction, `schema_violation` trace event (`TraceEvent.SCHEMA_VIOLATION`).
- [x] 4. Remove the old lenient parser (it also crashed on an unclosed fence).
- [x] 5. Tests: `StructuredOutputEnforcementTest` (9): valid, wrapped in chatter or an unclosed fence, YAML then JSON (second attempt used and told why), retries exhausted into `on_failure`, missing field / wrong enum word / wrong nested type by path, cut-off JSON, extra fields and numbers as text, no schema (raw text kept), a step's own `expecting`.
- [x] 6. Guide: "What is checked" under Output Schema Enforcement.
- [ ] 7. Mutation check on `StructuredAnswer.problems` (flip required to optional: a test must fail).
- [ ] 8. Provider JSON mode for tool-less agents (follow-up spec).
