# Phase 36: the implementation prompt

Paste everything below the line into a new session. The owner starts the session; nothing here is a decision. Phase 35 is merged, so phase 36 can start.

---

You are implementing **phase 36** of llm4j: integrations, making any system easy to plug in and safe by default. The spec is complete and every decision is taken by the owner. Do not open a pull request unless the owner asks, and do not merge.

**Start from the latest `origin/main`.** Phase 35 is merged there (checked on 2026-11-14: `ToolKind.profile`; `DiscoveredKinds`, the `ServiceLoader` discovery of kinds; the `sandbox`, `workspace`, `compute`, `verify` and `scan` kinds; the extras `browser`, `documents`, `git` and `search`; the `docs/TOOLS_CATALOG.md` catalog and `src/tools/ai-agent4j-tools/docs/writing-a-tool.md`). Confirm it yourself (`VERIFICATION.md` R15) and **stop and tell the owner if anything the spec builds on is missing.**
**Get the spec in without merging its branch:** run `git fetch origin phase-36-spec` and then `git checkout origin/phase-36-spec -- docs/specs/36-integrations`, add the two phase 36 rows to `docs/specs/README.md` by hand (the index table and the status table), and commit it as `docs: add the phase 36 spec`. **Do not merge the branch:** it carries older copies of phase 35's documents, which would conflict with the merged ones.

**Read first, in this order:** `CLAUDE.md` and the `steward` skill (they win on conventions); then `docs/specs/36-integrations/`: `PRINCIPLES.md`, `DECISIONS.md` (D1 to D18, all taken), `DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`. Run `make setup` once at the start.

**How to work**
- Follow the sub-phases of `DESIGN.md` §3 in order: 36a to 36f. **Stop after 36a** (the one gate): report what you built and found, and wait for the owner before continuing.
- Test first. For each guard in `TEST_STRATEGY.md` §3 (G69 to G96), write the failing test before the code, and break the guard on purpose once to prove the test fails.
- **Fidelity (D15) is a requirement:** the fakes are wire-level, with real TLS and verification on; MCP messages are validated against the official schema of their revision; fixtures are real, recorded, scrubbed and carry metadata; run the differential tests in the integration tier where a network and a container runtime exist, and say what ran.
- Do the reads R1 to R18 of `VERIFICATION.md` §0 at the sub-phase listed, and write each result as a short note under §0. **R2 (does `swagger-parser` fetch on its own?) and R3 (do MCP tools reach an agent without the approval wrappers?) come first in 36a.** **If a read shows that a decision cannot be built as written, stop, write the finding under "Where the code contradicts a decision", and ask the owner. Do not deviate silently.**
- **The MCP specification must be read again from the official pages when 36b starts** (and the annotation fields on the schema page now, R5): never take the specification from memory.
- Docs are part of every change (`CLAUDE.md` principle 7), and 36f is the whole pass with nothing deferred (D17), including `docs/TOOLS_CATALOG.md` (it already lists `openapi` and `mcp`: correct and extend it) and `src/tools/ai-agent4j-tools/docs/writing-a-tool.md`. Run `make regen` and `make docs-check` after docs, commands, starters or rules.

**Tiers:** `-Dtest=` while iterating; `make test-core` after each sub-phase; `make test-fast` on every module touched at the end of each phase; `make format-check`; `make test-oracle` once at the end. **Never run `make test-full` or `make test-live`.** Never lower a coverage limit, add an exclusion or `@Disabled` a test. The new `mutation-integrations` profile must reach 85%.

**Security rules that must hold:** no real credential anywhere (fixtures are scrubbed and `scan` runs over them; tests use obviously fake values); a token is never logged, stored or refreshed by llm4j (D8); no TLS or hostname check is ever weakened, even in a test; every outbound call passes `NetPolicy`; a `command` value is always one argument; `connect` never writes a secret and never writes a draft that failed its gates; specifications, help text and server output are untrusted input.

**The `substitution` setting is being removed by the owner** (at the time of writing the parser and the guide on `main` still mention it): no example, fixture, starter or guide text you write may use `substitution:`, and nothing you write may bring it back or depend on it. Follow whatever the merged phase 35 documents for how variables are replaced in texts (`VERIFICATION.md` R18); the `{name}` placeholders of a `command` template are the kind's own and never go through that replacement (D9 rule 6).

**Compatibility:** scripts written before this phase must still load and run, with warnings (D2, D3, D7); the old core OpenAPI classes are deprecated, not removed; `credential_rejected` is an additive enum value; record both in `docs/API_COMPATIBILITY.md` with migration notes.

**A container runtime may be available:** the cloud image has Docker, but its daemon is not running at session start (see phase 35's `VERIFICATION.md` probes: start `dockerd` as root and wait for `/var/run/docker.sock`). Docker Hub may answer 429 on a first pull: use one small image, pull it once and retry. If it cannot be started, report the integration tests and the container isolation of MCP servers as **not verified**, not as passing.

**Commits:** conventional prefixes, one concern each. Push to the branch the owner named. End each sub-phase with a report following `VERIFICATION.md` §2: the level reached and the evidence per row, what was not run, which risks became real, and what each R-item found.
