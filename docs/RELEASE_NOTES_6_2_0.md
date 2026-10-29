# Release notes: 6.2.0

Status: **in progress** (the field findings program, [docs/specs/FIELD_FINDINGS.md](specs/FIELD_FINDINGS.md)). This page lists what is finished so far; each item says what changed for you.

## Fixed: what a script declares now reaches the run

- **`shared_prompt` is applied.** In 6.1.0 and earlier no `weave` command (`run`, `eval`, `test`, `serve`) told the agents the shared prompt, in either form (`shared_prompt: "text"` or
  `shared_prompt: prompt "house-rules"`). It is now put first in every agent's system text. If your workflow relied on rules in the shared prompt, they were not in force before; check a run's behaviour
  after upgrading. See the correction in [RELEASE_NOTES_6_1_0.md](RELEASE_NOTES_6_1_0.md).
- **`weave check` no longer calls a named shared prompt file "not used by any agent".** It was the same fault.
- **`supervise` and `intervention` blocks are applied.** A script's own `supervise Agent { ... }` rules and `intervention { ... }` defaults were dropped when the script was loaded, so a supervised workflow ran
  unsupervised and `weave explain` did not list them. They now apply, including `supervise` blocks in imported files. An imported file's `shared_prompt` is not imposed on the file that imports it.
