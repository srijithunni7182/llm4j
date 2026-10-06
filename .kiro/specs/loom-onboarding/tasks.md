# Implementation Plan: Onboarding

- [x] 1. One-line fix: accept `%` in the editor scanner, with a test using `warn_at: 80%` (R1.3). Also found and fixed by the repo-wide scan (R1.5): an apostrophe inside a word ("doesn't"), which `Lexer.java` accepts
- [x] 2. `weave check --format json` and `--no-env`; editor diagnostics from it; the all-repo `.loom` parity test (R1.1, R1.2, R1.4, R1.5, R6.3) — Done. Deviations: editor problems are refreshed when a file is opened or saved (an unsaved buffer is not checked); `weave check --format json` cannot attribute a problem in an imported file, so all are shown on the entry file
- [x] 3. New check warnings: unused variable, unused human answer, same-next-step after a decision, `--strict` (R6) — Done (`WorkflowLint`, `--strict`)
- [x] 4. Audit info finding for a person running untrusted-derived output (R7) — Done (LA16, info)
- [x] 5. `weave init` with templates `pipeline`, `approval`, `classifier`; CI that runs each (R2.1, R2.2, R2.4, R2.5) — Done (templates are run in CI-style tests and by `scripts/verify-kit.sh`)
- [x] 6. `--with-java-tests` overlay: pom with a working surefire, loader on `EvalScenarios.fromDirectory`, wiring test, fail-on-zero-tests (R2.3, R4) — Done; checked with real Maven by `scripts/verify-java-tests-template.sh`
- [x] 7. Guide rewrite: inline examples, path fork, advice, Hexamind to a case study, stable links, link test (R3, R8) — Done for the guide, chapters and links (`GuideLinksTest`). Deviation: the Hexamind chapter content was only partly rewritten as a case study; later chapters still use it as an example
- [x] 8. `weave guide`; bundle the guide into the jar and the extension (R3.2) — Done (`weave guide`, bundled in the jar; extension commands **Loom: Open Guide** and **Loom: Install the Loom Skill in This Project**, with tests)
- [x] 9. `scripts/doctor.sh` and the build notes (R5) — Done (`scripts/doctor.sh`; build notes in chapter 6)
- [x] 10. Skill update as in `design.md` section 6, with the command-and-chapter test (R9.1, R9.2) — Done (`SkillKitTest` checks every command, option and chapter the skill names)
- [x] 10a. The kit: guide, templates and docs inside the jar; extension command to install the skill and the guide; skill free of repository paths; `scripts/verify-kit.sh` (R10.1 to R10.5) — Done (`scripts/verify-kit.sh` runs from the jar alone in an empty folder)
- [ ] 11. NOT DONE by Claude: needs a real VS Code and real models, which this session does not have. The scripted `scripts/verify-kit.sh` was run instead.  Cold-start rerun, in an empty folder with only the jar, the extension and the skill and no route to the repository (R10.6), of the three exercise requests against the finished work; compare with `findings.md`; record in `evidence/` (R9.3)
