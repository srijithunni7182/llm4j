# G7: documentation and tooling match reality

Code under test: fa18b98.

| Step | What was done | Result |
|---|---|---|
| (a) Guide blocks | `GuideExamplesTest` reads the "Generic Tools" section of `LOOM_GUIDE.md`, extracts every fenced `loom` block and validates each one | Passes (3 tests). A block that stops parsing or fails the load-time checks fails the test, so the guide cannot drift |
| (b) Documented commands | The same class runs the guide's commands as real `weave` subprocesses: `check`, `schedule sync`, `triggers list`, `triggers install` without `--apply` (V10.5, V10.7) | Pass. `DigestSampleTest` (3 tests) runs the sample scripts and `run.sh`'s commands |
| (c) Editor | `src/loom/vscode-loom/src/lsp/server.ts` hover entries for the six kinds and their options. Checked only that the file transpiles with no syntax diagnostics (TypeScript) | **Partly verified.** The extension's dependencies are not installed here, so the language server was not started and hover/completion were not exercised. The grammar has no list of kinds, and completion does not list kinds |
| (d) Build from the guide alone | The digest sample was built from the guide's examples and run end to end with `scripts/g5/run_g5.sh` (R1, R2) | Worked; no source reading was needed to get the sample to run. This was done by the implementer, who knew the code, so it is weaker than an independent read |
| (e) Kinds named everywhere | grep for `webhook`, `email`, `http`, `file`, `shell`, `sql` in `README.md`, `src/loom/README.md`, `src/loom/ai-agent4j-loom/README.md`, `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, the gap analysis and `server.ts` | All six names appear in all seven files (`KindsMentionedTest` also checks this) |

Defects found by this gate: none open. (Earlier in the work the guide's shell example was extended with a caution that the
allowed programs, not the arguments, are what an agent can reach.)
