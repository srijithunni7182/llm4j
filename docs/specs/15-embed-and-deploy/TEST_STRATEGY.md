# Phase 15 test strategy: embed and deploy

Status: **draft for review**. Requirements `R1`…`R33` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Generated code is compiled and run in the test.** Golden text files catch accidental change, but only compiling the output (with `-Xlint:all -Werror`) and *calling it against a scripted `LoomHost`* proves the bindings work. Every bindgen golden is also compiled; the corpus scripts are run through generated facades.
- **Generated files are tested by what they would do, not only by what they say.** Dockerfile/compose/k8s are parsed (YAML, Dockerfile grammar) and checked structurally for the safety properties (non-root, read-only filesystem, no published ports beyond the allowed, no secret values, an egress policy that equals the script's providers). Optional tool validation (`docker compose config`, `kubectl --dry-run=client`, `hadolint`) runs on demand in the manual checks.
- **Determinism everywhere.** Same input → byte-identical output is a test for every generator.
- **Secrets are the hazard.** A seeded environment with secret-shaped values is generated against and every output file is grepped.
- **Old behaviour is pinned.** The runtime, `LoomHost`, the starter's auto-configuration and `weave package` are unchanged; the whole corpus of existing scripts parses identically after the optional typed-parameter syntax lands.
- **Hostile text is data.** Workflow, field, agent and tool names containing Java keywords, Unicode, very long names, reserved words, collisions after case folding, must produce valid Java (escaped/renamed deterministically) or a clear error.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Shape inference** | Result shapes for single/multiple returns, nested objects, interpolations, `output_schema` agents, classifier enums (with `UNSURE`), `yes/no`, `rate`, task results, unknown → `String`; nullable on partial paths; no-return workflows | `SchemaInferTest` (one case per source of type), `ResultUnionNullableTest` | R3, R4, R9 |
| **Naming** | Java keywords, collisions, Unicode, case folding, leading digits, long names; stable renames | `NamingTest`, `NamingCollisionTest` | R1, R2 |
| **Golden + compile** | Generated sources equal goldens; compile with `-Xlint:all -Werror` on Java 17; no extra dependencies | `BindgenGoldenTest`, `BindgenCompilesTest`, `BindgenNoExtraDepsTest` | R1, R12 |
| **Run through facade** | Generated facade over a real `LoomHost` with scripted collaborators (phase 11): Done, Waiting (suspension and resume), Stopped, failure exception, builder options (run id, label, origin, invoker) | `FacadeRunsTest`, `FacadeSuspendResumeTest`, `FacadeFailureTest` | R5 to R7 |
| **Determinism / drift** | Byte-identical output; `--check` exits 1 on drift, 0 when current; no timestamps; import closure changes detected | `BindgenDeterministicTest`, `BindgenDriftTest` | R1, R10 |
| **Failure modes** | Script fails `check` → no partial output; indeterminate shape → map access with note | `BindgenCheckFailsTest`, `BindgenIndeterminateShapeTest` | R8, R9 |
| **Maven plugin** | Integration test with a sample project: generate-sources adds the root, build compiles, `failOnDrift` fails on drift | `MavenPluginBindgenIT` | R11 |
| **Typed params** | Old corpus parses identically; new syntax parses; defaults; validation errors at start with parameter names; call-site type checks; `weave test` and `run -i` validate alike | `CorpusParsesIdenticallyTest`, `TypedParamsParseTest`, `ParamsValidationTest`, `CallSiteTypesTest` | R13 to R15 |
| **Spring** | Annotation registers bean; generated vs runtime proxy; startup mismatch fails fast with nearest name; injected `WorkflowRuns`; `@LoomTest` slice swaps collaborators; no annotation → no change | `LoomWorkflowsBeanTest`, `StartupMismatchFailsFastTest`, `WorkflowRunsTest`, `LoomTestSliceTest`, `NoAnnotationNoChangeTest` | R17 to R21 |
| **Deploy plan** | Env names equal `weave check` credentials; egress equals audit data-flow providers; volumes and schedules derived | `DeployPlanTest`, `EgressFromScriptTest` | R22 |
| **Renderers** | Dockerfile, compose, k8s goldens; structural safety assertions (non-root, read-only fs, caps dropped, no host network/privileged, ports, internal networks, probes, limits) | `DockerfileGoldenTest`, `ComposeGoldenTest`, `K8sGoldenTest`, `SafetyPropertiesTest` | R22, R26, R27 |
| **Secrets** | Seeded env secrets never appear in any generated file; `.env.example` placeholders only | `NoSecretsInDeployFilesTest` | R23 |
| **Reproducibility / idempotence** | Byte-identical; digest pinning note; edited file detected, never overwritten silently; `--verify` drift | `DeployReproducibleTest`, `DeployNoOverwriteTest`, `DeployVerifyTest` | R24, R29, R30 |
| **Pre-flight** | HIGH finding stops; `--yes` continues and README records it; accepted trifecta carried | `DeployPreflightTest`, `TrifectaAcceptanceCarriedTest` | R25 |
| **Network posture** | Unsafe bind refused; default internal | `UnsafeBindRefusedTest` | R26 |
| **Skill** | Skill text matches commands/flags (drift test) | `SkillDriftTest` | R31 |
| **Architecture** | `bind/` and `deploy/` use the shared validator/graph/symbols and no parser; `deploy/` has no Docker/K8s/network dependency | `BindDeployDependencyTest` | R32, R33 |

## 3. Fixtures

- `src/test/resources/bind/scripts/` scripts exercising each source of type; goldens beside them.
- `src/test/resources/bind/sample-app/` a tiny Maven/Spring project that uses the generated facade.
- `src/test/resources/deploy/scripts/` scripts with: one provider; several providers + a `webhook` tool; schedules; knowledge base; memory; JDBC journal; an unaccepted trifecta; an accepted one.
- A seeded-secrets environment.

## 4. Fault injection

A script that changes between `bindgen` and `--check`; an import that disappears; two workflows whose names collide after Java naming; a field named `class`; a classifier question named `unsure`; an output directory that is read-only; `--out` containing a hand-edited Dockerfile; compose output with a Fabric image reference that has no digest; a journal volume path containing spaces/Unicode; a script with 100 workflows (generation time and file size bounded).

## 5. What is deliberately not tested in the build

- Actually building images or starting containers/clusters (manual checks, CI optional job).
- Cloud-specific behaviour (not generated).
- The wisdom of the default resource limits for a specific workload (documented as starting points).
