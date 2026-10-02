# G9: safety

| Check | Result |
|---|---|
| V5.1–V5.7: allowlist (chat and sender), codes, approvals need the code, control characters, replies as text only, the secrets sweep, a channel that cannot be used fails at start | tagged tests pass (G2: all 43 checks traced and passing) |
| V3.8: a reply written before its question is never its answer | found by the real-process run (G5 B2): a fresh store read a chat's earlier reply and applied it to a new question. Fixed (by message number, else date) and covered by a test and a sabotage mutation (M10) |
| Secrets sweep over the real-run transcripts and stores (`evidence/g5/B5-secrets.txt`) | the fake bot token appears in no file and no audit line |
| Security review of the new code (a focused read, not the sub-agent filtering step) | no finding at confidence 8 or above after the fix above |

What the review looked at, and why nothing else was raised:
- **Who can answer.** A reply counts only when both the chat id and the sending user's id are on the allowlist; an empty allowlist or a missing token fails the command at start. `weave answer` is a local operator command on files the operator owns.
- **Codes and paths.** A code is six characters from 31 symbols (about 29.7 bits) from `SecureRandom`; `PendingStore.get` accepts only strings that look like a code, so a code can never be a path. Records are written atomically under a lock held across threads and processes.
- **Injection.** Messages go as plain text with no markup mode; questions and replies have control and direction-changing characters neutralised; a reply is only ever compared with the choices or stored as text, never run or used as a path. The `command` channel runs the program named in the store's own `channel.json` (operator-controlled, like the script) and passes the question on stdin as JSON, not on the command line.
- **The token.** It is read from the environment, held in memory, scrubbed from every error, and appears in no file, audit line or log (V3.1, V5.6, G5 B5).
- **Blindness.** The channel adds nothing to the question the decide statement builds; the e2e test checks the question, the reminder and the confirmation against a hostile proposal.
- **Not a concern here.** Bot chats are not end-to-end encrypted; the guide says so. Resource use by a flood of replies is out of scope.
