# Phase 36: integrations: making any system easy to plug in, and safe by default

Status: **final for phase 36 (all decisions taken by the owner); not yet built.** Every choice is in [DECISIONS.md](DECISIONS.md). The facts about the code are in [DESIGN.md](DESIGN.md).
The principles of phases [33](../33-async-workflows/PRINCIPLES.md), [34](../34-supervision-signals/PRINCIPLES.md) and [35](../35-framework-tools/PRINCIPLES.md) all still hold (the model reasons and the code holds the authority; fail closed; every effect is journaled; a construct is finished only when it can be tested without a model, a real clock or racing threads; the framework stays light). **The owner has said this is the last framework phase for a while**, so it carries what the verticals need from integrations and nothing speculative.

## What this phase is for

Every idea we explored needs data from, or action in, systems that already exist: through an API, an MCP server, a file, a command, a document, a page. The workflow is the glue, and **how easily a system can be plugged in, safely, is the moat.** Breadth is not: other tools have thousands of connectors, and the MCP and OpenAPI ecosystems already supply breadth. What llm4j can add is the control layer over every integration: an effect and a reach for each operation, a journal, a replay, an audit entry, a fake for tests.

Reading the code (DESIGN §1) showed that the integration surfaces are uneven. `http` is mature; `openapi` is its weak sibling (no SSRF guard, no timeout, a 2,000-character cut on every answer); the MCP client reaches only local stdio servers, tools only; the audit's use of MCP hints is written and tested but never called; no flow of OAuth ships. So the first half of this phase **fixes what exists**, and the second half adds the reach and the authoring that make integration easy.

## The new principles

33. **An integration is safe by default or it is not offered.** Every outbound call of every kind passes through the same guards: the address rules of `NetPolicy`, a timeout, a size cap, redaction, retries that know "never sent" from "may have been". No kind has a weaker path of its own.
34. **The system describes itself; the script decides.** Effect, idempotency and reach are taken from what the system says about itself (an OpenAPI method, an MCP annotation, a typed parameter) and shown in the audit as *assumed*. The owner's declaration always wins, and an operation nobody described is taken for the worst case.
35. **Breadth is consumed, not built.** OpenAPI and MCP are the two doors. llm4j adds the control layer to what they bring; it does not write connectors.
36. **A model may draft an adapter; code verifies it against samples; a person approves what it may change.** Nothing a model drafted is trusted until it has passed the samples and the audit, and the effect classification is always a person's.
37. **A program started for an integration is a program, and is isolated like one.** An MCP server launched from a script, or a command adapter, runs under the isolation levels and the fail-closed rules of phase 35, never with the host's full privileges by omission.
38. **A credential is the consumer's, asked for on every request and never shown.** The consumer acquires a token by whichever grant it likes and hands it over; llm4j asks for it on every request, so the consumer can refresh it, and never stores, refreshes or logs one. A rejection is a typed outcome the consumer can act on.
39. **What the documentation promises, a test proves.** A statement in the guide about how a tool behaves has a test that would fail if the behaviour were not wired. (The MCP hints are the lesson.)
40. **Ease is measured.** Time to a first safe, tested, audited call is a number we measure on a fixed set of systems and publish.
