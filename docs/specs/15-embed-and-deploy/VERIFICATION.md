# Phase 15 verification plan: embed and deploy

Status: **15a to 15d done** (bindgen; Maven plugin; Spring; deploy docker and compose, and the k8s renderer); typed parameters deferred (D15); 15e (skill, docs, k8s checks) not started. Run top to bottom at the end of sub-phase 15e; the relevant rows after each sub-phase. Results in §7.

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
| R1 | `BindgenGoldenTest`, `BindgenDeterministicTest`, `NamingTest` | ✅ 15a |
| R2 | `RenameBreaksCompileTest`, `NamingCollisionTest` | ✅ 15a |
| R3 | `SchemaInferTest`, `ResultUnionNullableTest` | ✅ 15a |
| R4 | `SchemaInferTest` (each source of type) | ✅ 15a |
| R5 | `FacadeRunsTest`, `InputSerialisationTest` | ✅ 15a |
| R6 | `FacadeSuspendResumeTest`, `FacadeFailureTest` | ✅ 15a |
| R7 | `FacadeVariablesEscapeHatchTest` | ✅ 15a |
| R8 | `BindgenCommandTest.aScriptThatDoesNotCheckWritesNothing`, `DeployTest.thePackagesAreIndependent...` | ✅ 15a/15d |
| R9 | `BindgenIndeterminateShapeTest` | ✅ 15a |
| R10 | `BindgenDriftTest` | ✅ 15a |
| R11 | `BindgenMojoTest` (the goal run directly; a full Maven invoker run is manual check 1) | ✅ 15b |
| R12 | `BindgenCompilesTest`, `BindgenNoExtraDepsTest` | ✅ 15a |
| R13 | `CorpusParsesIdenticallyTest`, `TypedParamsParseTest` | ⏸ deferred (D15) |
| R14 | `ParamsValidationTest`, `CallSiteTypesTest` | ⏸ deferred (D15) |
| R15 | `ParamDefaultsTest` | ⏸ deferred (D15) |
| R16 | decision recorded: typed parameters are **not** shipped in this phase (D15) | ✅ 15b |
| R17 | `LoomWorkflowsTest` (scanned interface runs its workflow; a facade is built with `of`) | ✅ 15c |
| R18 | `LoomWorkflowsTest` (no matching workflow, wrong parameters, wrong return type each stop the start) | ✅ 15c |
| R19 | `LoomWorkflowsTest.workflowRunsLooksAtRuns...` | ✅ 15c |
| R20 | `LoomWorkflowsTest` (`@LoomTest` runs a workflow with no model and no key) | ✅ 15c |
| R21 | `LoomWorkflowsTest.anApplicationWithoutTheAnnotationsSeesNothingNew`, the starter's existing 146 tests | ✅ 15c |
| R22 | `DeployTest.thePlanIsReadFromTheScript`, `...aLocalModelNeedsNoKeyAndNoFixedHost` | ✅ 15d |
| R23 | `DeployTest.noSecretShapedValue...` (all three targets) | ✅ 15d |
| R24 | `DeployTest.theSameInputGivesTheSameBytes...` | ✅ 15d |
| R25 | `DeployCommandTest.openFindingsAreAWarning...`, `...requireCleanIsTheOptInGate` (an accepted finding is listed in the README from the review's own report; there is no language statement to accept a trifecta yet: D9) | ✅ 15d |
| R26 | `DeployTest.exposingFabricBeyondLoopbackIsRefused`, `...composeHasTheSafetyProperties...`, `...kubernetesManifests...`; `DeployCommandTest.anUnsafeFabricBind...` | ✅ 15d |
| R27 | `DeployTest.theEntrypointRunsTheDaemon...`, `...composeHasTheSafetyProperties...` (healthcheck, depends_on) | ✅ 15d |
| R28 | `DeployTest.stateGoesOnAVolumeUnlessToldOtherwise`, the README test | ✅ 15d |
| R29 | `DeployCommandTest.aHandEditedFileIsKeptUntilForced`, `DeployTest.anEditedFileIsRecognised...` | ✅ 15d |
| R30 | `DeployCommandTest.verifyPassesThenFlagsStaleMissingAndALostName` | ✅ 15d |
| R31 | `SkillDriftTest` | ⬜ 15e |
| R32 | `DeployTest.thePackagesAreIndependent...` | ✅ 15d |
| R33 | `DeployTest.thePackagesAreIndependent...` (renders text; `docker compose config` and `kubectl --dry-run` are manual checks) | ✅ 15d |

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

### 15a (2026-10-24)

- Built: `bind/` (`SchemaInfer`, `Naming`, `Bindgen`) and `weave bindgen SCRIPT --package P [--out DIR] [--facade N] [--nullability none|jspecify|optional] [--check]`. One facade file per script plus one `Outcome.java` per package; per workflow a method, a `…Call` builder (`runId`, `label`, `origin`, `invoker`), `resume…`, a result record; one enum per classifier question a result carries (with `UNSURE` when the classifier escalates; `ask` gives `YES`/`NO`); `answer(...)`. The script must pass `weave check` or nothing is written. Output is deterministic and has no dependency beyond Loom.
- Tests: `BindgenTest` 7 (compiles under `-Xlint:all -Werror`, same script same bytes, names and union, nullability choices, notes, package refusal, the facade **runs** a workflow and returns typed values), `BindgenCommandTest` 3 (write, `--check` passes and fails on drift, nothing written for a script that does not check, a renamed field changes the generated record so code using the old name stops compiling), `BindgenCorpusTest` 1 (every example script's bindings compile together).
- Deviations, to confirm (D15): `--nullability` defaults to `none`, not `jspecify`, because the generated code must need nothing beyond Loom (R12) and `jspecify` would add a dependency; `jspecify` and `optional` are one flag away. A field whose type the script does not determine (an agent's output, a task's result) is text, said in the code, never guessed; nested records from an agent's `output_schema` are not generated yet.

### 15b (2026-10-24)

- Built: a new module `loom-maven-plugin` (`io.github.srijithunni7182:loom-maven-plugin`, prefix `loom`): goal `loom:bindgen`, bound to `generate-sources`; `<scripts><script><file/><pkg/><facade/></script></scripts>`, `outputDirectory` (default `target/generated-sources/loom`, added as a source root), `nullability`, `failOnDrift` (writes nothing; fails the build naming the stale files when the checked-in bindings differ from the scripts).
- Tests: `BindgenMojoTest` 3 (writes and adds the source root; drift fails and leaves the files alone; missing script, bad option, no scripts).
- Typed workflow parameters (`workflow Main(message: string, priority: number = 3)`) are **not** built: a grammar change touches the parser, the validator, every tool that reads parameters, and the editor, and the bindings work without it (parameters are text, as the runtime's inputs are). D15 asks.

### 15c (2026-10-24)

- Built (starter): `@LoomWorkflows` (an interface whose methods are the script's workflows, found by scanning the application's packages; a method returns `RunOutcome` and takes one `String` per parameter), `@EnableLoomWorkflows(Facade.class)` (registers bindgen facades built with their `of(LoomHost)`), `LoomWorkflowFactory` (eager, so a mismatch stops the start naming the method and the nearest workflow), `WorkflowRuns` (`answer`, `resume`, `status`, `runs`), and `@LoomTest` with `ScriptedLoomModels` (agents answer what the test scripted, in order; any model key exists and is worthless).
- Tests: `LoomWorkflowsTest` 6; the starter's 146 tests pass (one existing test, `ZeroMagicTest`, lists the starter's beans that are inert without a Loom script; the two new inert ones were added to it).
- Deviations: the plan puts `script = "classpath:..."` on the annotation; the script stays `llm4j.loom.script` (one host per application, one place to set it). `@LoomTest` scripts agents only; classifiers and tools are tested with `weave test`. Typed `Outcome<T>` results come from the generated facade, not from the proxy form.

### 15d (2026-10-24)

- Built (`deploy/`, `weave deploy`): `DeployPlan` (env names from the models' vendors and from `env.` options of tools; hosts of the providers; schedules; whether a person is asked), renderers for a Dockerfile (non-root uid 10001, `weave.jar` copied in, healthcheck that re-checks the script, tag or digest said), `entrypoint.sh` (the daemon when the script has schedules, else `weave run`; reports to Fabric when `LOOM_FABRIC_URL` is set; reads `/run/secrets/*`), `.dockerignore`, `.env.example` (names only), `compose.yaml` (read-only root, all capabilities dropped, no-new-privileges, Fabric on an `internal: true` network and published on loopback, `depends_on` its healthcheck, a volume for journals), Kubernetes manifests (service account without a token, config map, **empty** secret template, PVC, Deployment and Fabric with non-root, read-only root filesystem, dropped capabilities, seccomp runtime default, a NetworkPolicy), and a README (needs, review state, failure cases). Each file carries a stamp (script hash and a hash of its body), so a hand edit is detected. `weave deploy SCRIPT --target docker|compose|k8s [--out] [--name] [--base-image] [--with-fabric] [--fabric-image] [--journal] [--expose] [--schedule] [--fabric-publish] [--require-clean] [--dry-run] [--force] [--verify DIR]`.
- Tests: `DeployTest` 15, `DeployCommandTest` 9 (including the review warning on a script with a real high finding: exit 0, and `--require-clean`: exit 1 with nothing written).
- Deviations, to confirm (D15): the image is built from the project folder plus a `deploy/weave.jar` you copy in (deploy does not know how weave was installed, and does not run `weave package`); the Kubernetes NetworkPolicy cannot name hosts, so it allows port 443 outside the private ranges only when the script uses a hosted model and lists the hosts in an annotation for an egress proxy or a policy engine that can name them; the Fabric image defaults to `loom-fabric:6.0.0` (a tag you build or mirror; no image is published by this project that deploy could rely on).
