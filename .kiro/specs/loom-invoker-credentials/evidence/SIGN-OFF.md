# Sign-off: Loom invoker credentials

Evidence commits: G1 and G3 at `0f74a1b`, G2 and G4–G9 at `9a1f903`. The two differ only in the evidence files (`git diff 0f74a1b 9a1f903 -- . ':!.kiro'` is empty).
Re-run everything with `scripts/verify-invoker-credentials.sh`; one gate with `scripts/verify-invoker-credentials.sh G3`.

## What was built

A script can say `auth_value: invoker.token` on an `openapi` tool. The application passes an `InvokerCredentials` to `executeWorkflow(name, inputs, invoker)`; the tool asks it for
the value on every request, so the host can refresh an expiring token with the user's refresh token. A 401 makes the tool tell the host (`invalidate`), ask again and repeat once.
The credential belongs to the run (a `ThreadScope` snapshot is installed on every worker thread and removed afterwards), goes only over https or to this machine, and is never written down.
A run that needs credentials it was not given stops before its first step. An executor refuses a second outermost run. `weave run|resume --invoker-env NAME=VARIABLE`,
`weave check` (lists the credentials a script takes) and `weave audit` (LA17) know about it. Design: [design.md](../design.md) (§As built lists where the build differs from the draft).

## Gates

| Gate | Result | Evidence |
|---|---|---|
| G1 build | The whole reactor compiles. Tests of the three modules pass in order and in random order: core 869 (2 skipped, as before), tools 364, loom 1686 (1 skipped, as before); 0 failures | [G1-build.txt](G1-build.txt) |
| G2 traceability | 46 checks in `verification.md`, every one with a passing tagged test (V6.5 is this gate's G4); no test with a made-up id; none disabled or without an assertion | [G2-traceability.txt](G2-traceability.txt) |
| G3 mutation | PIT on the lines changed since the base: `ai-agent4j` 56/56 mutants killed, `ai-agent4j-loom` 124/124 killed (target was 85%; none survive, none to triage) | [G3-mutation.txt](G3-mutation.txt) |
| G4 regression | No existing test file was modified or deleted. Baseline 2372 test methods, now 2503 (131 added); none missing, none that passed before and fails now | [G4-regression.txt](G4-regression.txt) |
| G5 end to end | Two people log in (fake OAuth), one script, two workflows at the same time. Ann's access token is refused (401) after one call, replaced with her refresh token, and her run carries on; Bob's is untouched; no request carried one person's token for the other's question | [g5/D1-two-users.txt](g5/D1-two-users.txt) |
| G6 package | The CLI packages and `weave check` on a script that uses `invoker.token` says which credential it takes | [G6-package.txt](G6-package.txt) |
| G8 coverage | Every new class at or above 90% lines and 85% branches; most at 100% | [G8-coverage.txt](G8-coverage.txt) |
| G9 safety | The V4 and V5 tests (where a credential may go; never written down) all passed | [G9-safety.md](G9-safety.md) |

Coverage is also enforced at build time: JaCoCo rules in the two module poms fail `mvn verify` below the same limits for the new classes. Mutation testing is a profile:
`mvn -Pmutation-invoker org.pitest:pitest-maven:mutationCoverage`, judged with `scripts/pit_survivors.py <report> --since <base>`.

## Things to know

- **`mvn verify` of `ai-agent4j-loom` fails, and failed before this work:** the existing JaCoCo rule on `execution.Decider` wants 85% of lines and gets 80% (checked at the baseline commit too). It is not touched by this change and was left alone. The new rules pass.
- **An executor still runs one workflow at a time.** The second outermost `executeWorkflow` throws `IllegalStateException` (decided in review). Making the executor reentrant is a separate spec; `ThreadScope` is the first piece of it.
- **`invoker.NAME` is only for `auth_value` of `openapi`.** The `http` tool, `header.*` options and model providers refuse it with a message (task 12 is the `http` follow-up).
- **A credential from the invoker is held to the https rule; static keys and stored secrets are not,** so nothing that worked before stops working.
- **`approve:` needs a `HumanInterface`** on the executor. The documentation example check found that a web application has to supply one; the guide now says so.
- **Not done (optional tasks):** 12 (`http` kind) and 13 (the reentrant executor).
- **Not run:** nothing here touches a real identity provider or a real API; the demo uses local servers. The JavaScript tests of `vscode-loom` were not run (only its hover text changed, which a Java test checks).
