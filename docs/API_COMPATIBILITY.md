# API Compatibility Policy

This document defines the compatibility guarantees for `ai-agent4j` and companion libraries.

## Versioning

- Public artifacts follow semantic intent using the canonical coordinates in `VERSION_MATRIX.md`.
- Major versions may contain breaking API changes.
- Minor versions are expected to remain source-compatible for public APIs.
- Patch versions are bug/security fixes with no intended API breaks.

## Public API Surface

The following packages are considered public and stable targets for consumers:

- `io.github.llm4j` (client entry points)
- `io.github.llm4j.model`
- `io.github.llm4j.config`
- `io.github.llm4j.agent` and `io.github.llm4j.agent.tools`
- `io.github.llm4j.provider`
- `io.github.llm4j.mcp`

eval4j's `io.github.llm4j.eval.dataset.EvalScenario` is a record that grows by adding components at the end. Each time it does, the previous constructor stays
(the 7-, 10-, 12-, 13- and 14-argument forms all still compile and behave as before), and a new accessor has a safe default (`namedInputs()` and `mustNotContain()` are never
null; `expectedMinWords()` and `expectedMaxWords()` are null when no limit is set). Code that builds a scenario with `new EvalScenario(...)` or reads its accessors is unaffected; code that destructures the record by its full component list
(pattern matching) is the one case that must change.

Internal implementation details may change without notice, especially:

- `io.github.llm4j.http`
- `io.github.llm4j.util`
- app-specific packages outside core libraries

## Deprecation Policy

- New replacements should be introduced before removing old APIs.
- Deprecated APIs should stay available for at least one minor release cycle when feasible.
- All deprecations must include migration notes in release documentation.

## Breaking Change Requirements

Before introducing a breaking change:

1. Document the impact in `MIGRATION_GUIDE_5_0.md` (or next migration guide).
2. Add compatibility notes in PR description.
3. Update examples and wiki pages in the same change set.
