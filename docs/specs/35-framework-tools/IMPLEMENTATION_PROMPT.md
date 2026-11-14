# Phase 35: the implementation prompt

Paste everything below the line into a new session on branch `ccr-08cda518-w8aqfn` (it already holds the spec). The owner starts the session; nothing here is a decision.

---

You are implementing **phase 35** of llm4j: self-describing tool kinds and a wider tool set. The spec is complete and every decision is taken by the owner. Work on branch `ccr-08cda518-w8aqfn` (it already holds the spec). Do not open a pull request unless the owner asks, and do not merge.

**Read first, in this order:** `CLAUDE.md` and the `steward` skill (they win on conventions); then `docs/specs/35-framework-tools/`: `PRINCIPLES.md`, `DECISIONS.md` (D1 to D22 taken, D10 folded into D11, D23 deferred), `DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`. Run `make setup` once at the start.

**How to work**
- Follow the sub-phases of `DESIGN.md` §4 in order: 35a, 35b, 35c, 35d, 35e, 35f. **Stop after 35a** (the one gate): report what you built and found, and wait for the owner before continuing.
- Test first. For each guard in `TEST_STRATEGY.md` §3, write the failing test before the code, and break the guard on purpose once to prove the test fails. In 35a the **characterization test of the audit output (G36) is written and green before any refactoring**; if the output differs after, report the difference, do not resolve it.
- The first commit of 35a is **the move to `src/tools/`** (D4) with nothing else in it: update every script, Makefile target and document path, and keep the artifact coordinates unchanged.
- Do the reads R1 to R15 of `VERIFICATION.md` §0 at the sub-phase listed, and write each result as a short note under §0. **If a read shows that a decision cannot be built as written, stop, write the finding under "Where the code contradicts a decision", and ask the owner. Do not deviate silently.**
- Docs are part of every change (`CLAUDE.md` principle 7), and 35f is the whole pass with nothing deferred (D20): the VS Code hover text, two starters, the tutorial chapter, the skills. Run `make regen` and `make docs-check` after docs, commands, starters or rules.

**Tiers:** `-Dtest=` while iterating; `make test-core` after each sub-phase; `make test-fast` on every module touched at the end of each phase; `make format-check`; `make test-oracle` once at the end. **Never run `make test-full` or `make test-live`.** Never lower a coverage limit, add an exclusion or `@Disabled` a test. The new `mutation-tools` profile must reach 85%.

**Security rules that must hold:** no real credential anywhere (tests use obviously fake values); `scan` never echoes a match; secrets reach a sandbox only through a named `SecretRef`; no TLS or hostname check is ever weakened; no `allow_loopback` switch (D7); a request for `container` never falls back to `process` (D5); the light tools module gains no dependency (D4, G40).

**A container runtime is in the cloud image.** `docker` 29.9.2 with `dockerd`, `containerd` and `runc` are installed, but the daemon is not running at session start. Start it as root with `nohup dockerd > "$SCRATCH/dockerd.log" 2>&1 &` and wait for `/var/run/docker.sock`. There is nothing to install. What was probed ad hoc is in `VERIFICATION.md` §0 ("Probes done while writing the spec"): `--network none`, an `--internal` network with no route out, and a host listener bound on the internal network's gateway address being reachable from a container on it. Docker Hub may answer 429 (a rate limit) on a first pull: use one small image, pull it once and retry. Docker Desktop and rootless Podman are not available here: do not claim them. The proxy spike (R11) is the first task of 35d: turn the ad hoc probe into the `@Tag("integration")` test, and write the verified platforms into the docs. If the daemon cannot be started in a session, report the container backend and the proxy as **not verified**, not as passing.

**Commits:** conventional prefixes, one concern each. Push to this branch. End each sub-phase with a report following `VERIFICATION.md` §2: the level reached and the evidence per row, what was not run, which risks became real, and what each R-item found.
