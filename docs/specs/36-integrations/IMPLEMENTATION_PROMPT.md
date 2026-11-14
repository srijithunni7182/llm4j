# Phase 36: the implementation prompt

Paste everything below the line into a new session. The owner starts the session; nothing here is a decision. **Phase 36 starts only after phase 35 is merged.**

---

You are implementing **phase 36** of llm4j: integrations, making any system easy to plug in and safe by default. The spec is complete and every decision is taken by the owner. Do not open a pull request unless the owner asks, and do not merge.

**First check the starting point.** Phase 36 builds on phase 35. Confirm on the latest `main` that phase 35 is merged: `ToolKind.profile`, the kind registry with `ServiceLoader` discovery, the `sandbox` and `workspace` kinds, and the extras structure under `src/tools/` exist as phase 35's spec says (`VERIFICATION.md` R15). **If they do not, stop and tell the owner; do not start.** The phase 36 spec is on the branch `phase-36-spec`; work on the branch the owner names for this session, and bring the spec files in by merging `phase-36-spec` (docs only) so they are in your tree.

**Read first, in this order:** `CLAUDE.md` and the `steward` skill (they win on conventions); then `docs/specs/36-integrations/`: `PRINCIPLES.md`, `DECISIONS.md` (D1 to D18, all taken), `DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`. Run `make setup` once at the start.

**How to work**
- Follow the sub-phases of `DESIGN.md` §3 in order: 36a to 36f. **Stop after 36a** (the one gate): report what you built and found, and wait for the owner before continuing.
- Test first. For each guard in `TEST_STRATEGY.md` §3 (G69 to G96), write the failing test before the code, and break the guard on purpose once to prove the test fails.
- **Fidelity (D15) is a requirement:** the fakes are wire-level, with real TLS and verification on; MCP messages are validated against the official schema of their revision; fixtures are real, recorded, scrubbed and carry metadata; run the differential tests in the integration tier where a network and a container runtime exist, and say what ran.
- Do the reads R1 to R17 of `VERIFICATION.md` §0 at the sub-phase listed, and write each result as a short note under §0. **R2 (does `swagger-parser` fetch on its own?) and R3 (do MCP tools reach an agent without the approval wrappers?) come first in 36a.** **If a read shows that a decision cannot be built as written, stop, write the finding under "Where the code contradicts a decision", and ask the owner. Do not deviate silently.**
- **The MCP specification must be read again from the official pages when 36b starts** (and the annotation fields on the schema page now, R5): never take the specification from memory.
- Docs are part of every change (`CLAUDE.md` principle 7), and 36f is the whole pass with nothing deferred (D17). Run `make regen` and `make docs-check` after docs, commands, starters or rules.

**Tiers:** `-Dtest=` while iterating; `make test-core` after each sub-phase; `make test-fast` on every module touched at the end of each phase; `make format-check`; `make test-oracle` once at the end. **Never run `make test-full` or `make test-live`.** Never lower a coverage limit, add an exclusion or `@Disabled` a test. The new `mutation-integrations` profile must reach 85%.

**Security rules that must hold:** no real credential anywhere (fixtures are scrubbed and `scan` runs over them; tests use obviously fake values); a token is never logged, stored or refreshed by llm4j (D8); no TLS or hostname check is ever weakened, even in a test; every outbound call passes `NetPolicy`; a `command` value is always one argument; `connect` never writes a secret and never writes a draft that failed its gates; specifications, help text and server output are untrusted input.

**Compatibility:** scripts written before this phase must still load and run, with warnings (D2, D3, D7); the old core OpenAPI classes are deprecated, not removed; `credential_rejected` is an additive enum value; record both in `docs/API_COMPATIBILITY.md` with migration notes.

**A container runtime may be available:** the cloud image has Docker, but its daemon is not running at session start (see phase 35's `VERIFICATION.md` probes: start `dockerd` as root and wait for `/var/run/docker.sock`). Docker Hub may answer 429 on a first pull: use one small image, pull it once and retry. If it cannot be started, report the integration tests and the container isolation of MCP servers as **not verified**, not as passing.

**Commits:** conventional prefixes, one concern each. Push to the branch the owner named. End each sub-phase with a report following `VERIFICATION.md` §2: the level reached and the evidence per row, what was not run, which risks became real, and what each R-item found.
