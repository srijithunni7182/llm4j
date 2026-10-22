# Phase 15 verification plan: embed and deploy

Status: **not started**. Run top to bottom at the end of sub-phase 15e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | From a script I get a Java facade whose workflow names, inputs and result fields are checked by the compiler; renaming one in the script breaks the build that used it | `BindgenCompilesTest`, `RenameBreaksCompileTest` |
| A2 | Pausing for a person and resuming is explicit in the type (`Done | Waiting | Stopped`) and a `switch` is exhaustive | `FacadeSuspendResumeTest` |
| A3 | Generated bindings cannot drift unnoticed: `--check` and the Maven goal fail on drift | `BindgenDriftTest`, `MavenPluginBindgenIT` |
| A4 | Output is deterministic and compiles cleanly on Java 17 with no extra dependency | `BindgenDeterministicTest`, `BindgenNoExtraDepsTest` |
| A5 | In Spring, an annotated interface is a bean; a mismatch with the script fails at startup naming the nearest workflow | `LoomWorkflowsBeanTest`, `StartupMismatchFailsFastTest` |
| A6 | An application's integration test can run workflows with no model and no keys | `LoomTestSliceTest` |
| A7 | Applications that do not use the new features are unchanged | `NoAnnotationNoChangeTest`, existing starter tests |
| A8 | `weave deploy` produces a container recipe, compose and Kubernetes files from the script, with env names, egress, volumes and schedules derived from it | `DeployPlanTest`, `DockerfileGoldenTest`, `ComposeGoldenTest`, `K8sGoldenTest` |
| A9 | No secret value ever appears in a generated file, an image recipe or a manifest | `NoSecretsInDeployFilesTest` |
| A10 | Generated deployments are safe by default: non-root, read-only root filesystem, dropped capabilities, internal Fabric, egress limited to the script's providers | `SafetyPropertiesTest`, `NetworkPolicyFromScriptTest`, `UnsafeBindRefusedTest` |
| A11 | Review findings never block a deployment: open ones are shown as a warning and recorded in the README, `--require-clean` is the opt-in gate, and an accepted trifecta is carried into the README | `DeployPreflightTest`, `TrifectaAcceptanceCarriedTest` |
| A12 | Generation is reproducible, idempotent, non-destructive and verifiable | `DeployReproducibleTest`, `DeployNoOverwriteTest`, `DeployVerifyTest` |
| A13 | The existing corpus parses identically after optional typed parameters (if approved) | `CorpusParsesIdenticallyTest` |
| A14 | Documented; the deployer skill uses `weave deploy` | docs review (§5), `SkillDriftTest` |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `BindgenGoldenTest`, `BindgenDeterministicTest`, `NamingTest` | ⬜ 15a |
| R2 | `RenameBreaksCompileTest`, `NamingCollisionTest` | ⬜ 15a |
| R3 | `SchemaInferTest`, `ResultUnionNullableTest` | ⬜ 15a |
| R4 | `SchemaInferTest` (each source of type) | ⬜ 15a |
| R5 | `FacadeRunsTest`, `InputSerialisationTest` | ⬜ 15a |
| R6 | `FacadeSuspendResumeTest`, `FacadeFailureTest` | ⬜ 15a |
| R7 | `FacadeVariablesEscapeHatchTest` | ⬜ 15a |
| R8 | `BindgenCheckFailsTest`, `BindDeployDependencyTest` | ⬜ 15a |
| R9 | `BindgenIndeterminateShapeTest` | ⬜ 15a |
| R10 | `BindgenDriftTest` | ⬜ 15a |
| R11 | `MavenPluginBindgenIT` | ⬜ 15b |
| R12 | `BindgenCompilesTest`, `BindgenNoExtraDepsTest` | ⬜ 15a |
| R13 | `CorpusParsesIdenticallyTest`, `TypedParamsParseTest` | ⬜ 15b |
| R14 | `ParamsValidationTest`, `CallSiteTypesTest` | ⬜ 15b |
| R15 | `ParamDefaultsTest` | ⬜ 15b |
| R16 | decision recorded (DESIGN §8.1) | ⬜ 15b |
| R17 | `LoomWorkflowsBeanTest` | ⬜ 15c |
| R18 | `StartupMismatchFailsFastTest` | ⬜ 15c |
| R19 | `WorkflowRunsTest` | ⬜ 15c |
| R20 | `LoomTestSliceTest` | ⬜ 15c |
| R21 | `NoAnnotationNoChangeTest` | ⬜ 15c |
| R22 | `DeployPlanTest`, `EgressFromScriptTest` | ⬜ 15d |
| R23 | `NoSecretsInDeployFilesTest` | ⬜ 15d |
| R24 | `DeployReproducibleTest` | ⬜ 15d |
| R25 | `DeployPreflightTest`, `TrifectaAcceptanceCarriedTest` | ⬜ 15d |
| R26 | `UnsafeBindRefusedTest`, `SafetyPropertiesTest` | ⬜ 15d |
| R27 | `ObserveFabricWiredTest` | ⬜ 15d |
| R28 | `DeployStateVolumesTest` | ⬜ 15d |
| R29 | `DeployNoOverwriteTest` | ⬜ 15d |
| R30 | `DeployVerifyTest` | ⬜ 15d |
| R31 | `SkillDriftTest` | ⬜ 15e |
| R32 | `BindDeployDependencyTest` | ⬜ 15a/15d |
| R33 | `BindDeployDependencyTest` | ⬜ 15d |

## 3. Quality gates

- All existing tests pass unmodified (runtime, starter, packaging).
- Line coverage ≥ 90 % in `bind/` and `deploy/`; mutation score ≥ 70 % on `SchemaInfer` and the safety-relevant renderers.
- Generated sources for the whole corpus compile under `-Xlint:all -Werror` on Java 17 and 21.
- `scripts/verify-embed-deploy.sh` (new): bindgen over the corpus + compile + facade runs; deploy over the sample scripts + structural safety checks + secret grep.
- No new runtime dependency in `ai-agent4j-loom`.

## 4. Manual checks

1. Add the Maven plugin to a scratch Spring Boot app; use the facade; rename a workflow field; watch the build break in the right place.
2. `weave deploy --target compose` for `ticket-triage`; `docker compose config` validates; `docker compose up` with the Fabric simulator keys; the app appears in Fabric; kill the app mid-run; it resumes.
3. `weave deploy --target k8s`; `kubectl apply --dry-run=server` on a scratch cluster; confirm NetworkPolicy blocks an unlisted egress.
4. Hand-edit the generated compose file, regenerate, and confirm the diff/confirmation behaviour.
5. Read the generated README as an operator who did not write the workflow.

## 5. Documentation owed

Wiki "Typed workflows in Java" and "Deploying a workflow"; `SPRING_BOOT_LOOM_GUIDE.md`; LOOM_GUIDE (typed parameters, if approved; `bindgen`; `deploy`); the deployer skill; `docs/PUBLISHING.md` (plugin release); Maven plugin README; `llms.txt`; the Spring starter README.

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §8 questions answered and recorded (typed parameters decision first)

## 7. Results

*(per sub-phase: corpus bindgen counts, compile results, structural-safety table of the deploy goldens, manual-check notes)*
