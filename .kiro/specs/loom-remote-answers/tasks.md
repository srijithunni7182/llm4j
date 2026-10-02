# Implementation Plan

**Prerequisite:** [loom-earned-autonomy](../loom-earned-autonomy/tasks.md) is done and signed off. Order: the pure pieces and the store first, then the channel interface and Telegram, then the runtime hook, the listener and the commands. Tests are written with each task. See [verification.md](verification.md).

- [ ] 1. **Prerequisite check** — confirm `HumanInterface.promptHuman(stepId, message)` may throw `RunSuspended`, that `Decider.ask`, the approval gate and the rewind prompt call it with a stable key, and that a `ResumeRun` trigger can be added from the command line.
- [ ] 2. **Pending records** (`io.github.llm4j.loom.channel`)
  - [ ] 2.1 `Question`, `Pending` (record, state), `PendingStore` (file, atomic write, lock, find by code and by run+step, list). V1.8, V2.3.
  - [ ] 2.2 `Codes` (secure, 25 bits, unique). V5.2.
  - [ ] 2.3 `Answers.record` (match, once, audit, resume trigger). V2.1, V2.3–V2.6, V5.1.
- [ ] 3. **Channel interface**
  - [ ] 3.1 `Channel`, `Sent`, `Reply`; `ConsoleChannel`; the contract test class. V6.1.
  - [ ] 3.2 `TelegramChannel` (send, long poll, offset, plain text, cut) with a base URL for the fake server. V3.1, V3.2, V3.6, V3.7.
  - [ ] 3.3 `CommandChannel`. V6.2.
  - [ ] 3.4 The fake Telegram server for tests (and for G5).
- [ ] 4. **Runtime hook**
  - [ ] 4.1 `HumanInterface` default method with hints; callers (decide, approvals, rewind) pass choices and kind. V1.2.
  - [ ] 4.2 `ChannelHumanInterface`: create, send once, suspend, return the answer. V1.1, V1.3–V1.5, V1.8.
  - [ ] 4.3 `WeaveEnv`/`Runs`/`WeaveCLI`: `--ask-via`, `channel.json`, start-up checks. V1.6, V1.7, V4.1, V5.7.
- [ ] 5. **Listener**
  - [ ] 5.1 `Listener.pollOnce`: allowlist, matching, confirmations, help. V3.3–V3.5, V5.3, V5.4.
  - [ ] 5.2 Reminders and expiry. V4.4, V4.5.
  - [ ] 5.3 `weave tick` polls once; `weave daemon` listens; backoff. V4.2, V4.3.
- [ ] 6. **Commands** — `weave answer`, `weave questions`, JSON output, exit codes. V2.1, V2.2, V4.6, V4.7.
- [ ] 7. **Safety suites** — hostile senders and replies, neutralising, the secrets sweep. V5.1–V5.7.
- [ ] 8. **Docs and tooling** — guide section, autonomy section, prompt, READMEs; checks. V7.1–V7.3. No sample.
- [ ] 9. **Quality** — JaCoCo rules, the sabotage driver for V8.3, regression run.
- [ ] 10. **Completion** — gate scripts, G5 against the fake server, evidence and `SIGN-OFF.md`.
- [ ] 11. *(optional, needs accounts)* Live checks L1–L2.
