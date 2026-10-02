# G9: safety

| Check | Result |
|---|---|
| V8.1–V8.6 hostile model, malformed proposals, neutralising, fail-closed on fault injection, forced level shown, secrets sweep | tagged tests pass (G2: all 79 checks traced and passing) |
| Secrets sweep over the real-run transcripts (`evidence/g5/`) | no credentials are used in those runs; nothing to find |
| Security review of the autonomy changes | no finding at confidence 8 or above (a focused review of the input-handling code, not the full diff and not the sub-agent filtering step) |

What the review looked at, and why nothing was raised:
- Decision names become directory names and table keys: `Names.check` (`[A-Za-z_][A-Za-z0-9_-]{0,127}`) is applied in the file ledger, the file level store, replay and the CLI, so no traversal is possible. Replay ids are generated, or checked on resume.
- The JDBC ledger and level store use prepared statements with bound parameters throughout; Jackson reads into `Map<String,Object>` with no default typing.
- `--policy` copies only files the operator's script names, into the replay's own directory (the target is checked to stay inside it).
- Proposals, reasoning and case fields are data only: they never become step ids, file names, conditions or tool calls, and what a person sees is neutralised. The autonomy store paths are reserved from the `file` and `shell` tools.
- A replay runs on an overlay journal in simulate mode, with unknown and effect tools simulated; the ledger, levels and live journals are byte-identical afterwards.
- Level changes (promote, demote, approve, freeze) are local operator commands on files the operator owns; each is audited, and a forced level is shown as forced.
