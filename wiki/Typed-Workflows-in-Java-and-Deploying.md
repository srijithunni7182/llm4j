# Typed workflows in Java, and deploying them

## Typed bindings

    weave bindgen triage.loom --package com.acme.triage --out src/main/java

writes `TriageWorkflows` (one method per workflow, a `…Call` builder with run id, label, origin and invoker, `resume…`, `answer`) and `Outcome<T>` (`Done`, `Waiting`, `Stopped`: a `switch` over it is exhaustive). A `return { team: "...", route: "..." }` becomes a record `MainResult(String team, String route)`; a field not set on every path is nullable (`--nullability jspecify|optional`); a classifier choice becomes an enum (with `UNSURE` when it escalates). A type the script does not determine is `String`, never a guess. The output is deterministic and needs nothing but Loom. `--check` fails when the files differ from the script; the Maven plugin does the same:

    <plugin>
      <groupId>io.github.srijithunni7182</groupId><artifactId>loom-maven-plugin</artifactId>
      <executions><execution><goals><goal>bindgen</goal></goals>
        <configuration><scripts><script><file>src/main/loom/triage.loom</file><pkg>com.acme.triage</pkg></script></scripts>
          <failOnDrift>false</failOnDrift></configuration></execution></executions>
    </plugin>

Rename a workflow or a returned field, regenerate, and the Java that used the old name stops compiling. (Typed workflow *parameters* in the language are not built; parameters are text.)

## Spring

    @LoomWorkflows interface Triage { RunOutcome main(String message); }          // checked against the script at start
    @EnableLoomWorkflows(TriageWorkflows.class) class App {}                       // a generated facade, built with of(host)

`WorkflowRuns` answers and resumes. In tests, `@LoomTest` swaps the models for `ScriptedLoomModels` (`models.agent("Writer", "a", "b")`): no model, no key.

## Deploying

    weave deploy main.loom --target compose --dry-run
    weave deploy main.loom --target compose            # or docker, or k8s

reads the script and writes text: a Dockerfile (non-root, read-only root filesystem friendly), `entrypoint.sh` (the schedule daemon, or `weave run`), `.env.example` (names only), a compose file or Kubernetes manifests (empty Secret template, NetworkPolicy), and a README with the review state and what each failure does. Put `deploy/weave.jar` (the same-version CLI) beside the Dockerfile and provide a Fabric image (`--fabric-image`). Secrets are never written. Fabric is published on loopback only; `--fabric-publish 0.0.0.0` is refused. Open review findings are a warning, never a stop (`--require-clean` is the opt-in gate). `--verify DIR` reports files that are stale or have lost a name the script needs; a file you edited is kept unless `--force`. Nothing is built or applied.
