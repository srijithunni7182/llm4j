# G9: safety

Commit at the time of writing: f6b627904d36c576f2be6512683aedaffebd5a22

| Check | Result |
|---|---|
| V8.1–V8.6 hostile-model, condition, neutralising, fork-never-writes-parent, unknown-class tool, secrets sweep | tagged tests pass (G2 traceability: all checks traced and passing) |
| Secrets sweep over the real-run transcripts (`evidence/g5/`): the fake webhook secret and its URL path appear nowhere | clean (`grep -rn "SECRETSECRET\|T000/B000" evidence/` finds nothing) |
| `/security-review` of the rewind and fork changes (journal boundary format, run lock, operator commands, fork and overlay, simulate, trigger hand-off, audit log) | no finding at confidence 8 or above |

What the review looked at, and why nothing was raised:
- Everything an operator types (`--to`, `--set`, `--reason`, paths) acts on files the operator already owns; the commands add no privilege. Values shown to a person pass `RunTravel.neutralise`; the audit log is written as JSON lines.
- Values a model can influence (carried feedback, reasons) are stored as journal data only; they never become step ids, file names or conditions (the `when` condition cannot call a model or a tool).
- A fork writes only to its own journal (overlay or copy), and a simulated fork persists `simulate` in its `run.json` so a later `weave resume` cannot perform effects.
- Tools of an unknown class are held under `ask first` and simulated in simulate mode by default.
