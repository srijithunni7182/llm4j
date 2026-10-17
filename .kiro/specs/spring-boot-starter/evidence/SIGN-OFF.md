# Sign-off: Spring Boot starter

Code under test: branch `ccr-6a8fd529-5vsj9m`; the gates were run at commits `116786ea` (G0, G2–G7, G9, G10) and `58c4bb15` (G1, after a change to the gate script only). Evidence files in this folder carry their commit.

| Gate | Result | Evidence |
|---|---|---|
| G0 tree clean, tasks ticked | pass | `G0-tree.txt` |
| G1 builds (plain, plain, random over the touched modules; extended with real pgvector) | pass: all libraries and the starter, 134 tests in the three-module build, 135 in the starter's extended pass | `G1-build.txt` |
| G2 traceability | pass: 142 of 142 checks have a passing tagged test | `G2-traceability.txt` |
| G3 coverage | pass: starter line 0.919 / branch 0.860; `loom.host` line 0.891 / branch 0.816; `CredentialProperty`, the module guard, `DurabilityGuard` at or above 0.93 branch | `G3-coverage.txt` |
| G4 dependency hygiene | pass: no Spring in any library; a consumer of the starter alone gets no Loom, addons, tools or spring-jdbc | `G4-dependencies.txt` |
| G5 sabotage | pass: 17 of 17 changes make their named checks fail | `G5-sabotage.md` |
| G6 plain-Java equivalence | pass | `G6-plain-java.txt` |
| G7 real process (kill -9, restart, answer; weave timeline; real loom-inspect page) | pass | `G7-real-process.txt` |
| G8 CI matrix JDK 17/21 × Spring Boot 3.2.3 / 3.5.6 | **not run here**: the workflow `.github/workflows/spring-starter.yml` is committed; the four runs happen when it first runs on GitHub | — |
| G9 regression against `185720cd` | pass: 4443 existing test methods, none lost or newly failing; no existing test file edited | `G9-regression.txt` |
| G10 safety | pass, with the review's findings fixed or explained | `G10-safety.md`, `G10-security-review.md` |

## What is not proved

- **G8**: JDK 21 and Spring Boot 3.5 were not run locally (the container has JDK 17 and Boot 3.2.3).
- **Live checks L1–L3** (real providers) were not run: no keys.
- `FabricEndToEndTest` in the unchanged `loom-inspect` module fails about one random order in three, with or without this work; the random-order pass of G1 therefore covers the modules this work touches.

## Deviations from the written spec (recorded in `verification.md`)

- The sabotage list has 17 entries; four of the original 21 had no single line to break in the final design (listed there with where they are covered).
- V10.7 tests a Spring-level run with credentials and a store sweep; resuming with new credentials is V8.22.
- G7 counts model calls (2, not 3) instead of a webhook post.
- Run ids are validated (letters, digits and `. _ @ -`), a hardening from the security review.
