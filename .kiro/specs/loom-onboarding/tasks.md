# Implementation Plan: Onboarding

- [ ] 1. One-line fix: accept `%` in the editor scanner, with a test using `warn_at: 80%` (R1.3)
- [ ] 2. `weave check --format json` and `--no-env`; editor diagnostics from it; the all-repo `.loom` parity test (R1.1, R1.2, R1.4, R1.5, R6.3)
- [ ] 3. New check warnings: unused variable, unused human answer, same-next-step after a decision, `--strict` (R6)
- [ ] 4. Audit info finding for a person running untrusted-derived output (R7)
- [ ] 5. `weave init` with templates `pipeline`, `approval`, `classifier`; CI that runs each (R2.1, R2.2, R2.4, R2.5)
- [ ] 6. `--with-java-tests` overlay: pom with a working surefire, loader on `EvalScenarios.fromDirectory`, wiring test, fail-on-zero-tests (R2.3, R4)
- [ ] 7. Guide rewrite: inline examples, path fork, advice, Hexamind to a case study, stable links, link test (R3, R8)
- [ ] 8. `weave guide`; bundle the guide into the jar and the extension (R3.2)
- [ ] 9. `scripts/doctor.sh` and the build notes (R5)
- [ ] 10. Skill update as in `design.md` section 6, with the command-and-chapter test (R9.1, R9.2)
- [ ] 11. Cold-start rerun of the three exercise requests against the finished work; compare with `findings.md`; record in `evidence/` (R9.3)
