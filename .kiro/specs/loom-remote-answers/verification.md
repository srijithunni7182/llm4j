# Verification Plan

The work is done when every check below passes in automated tests (unless marked *live*), the gates in §Completion pass at one commit, and the evidence is committed.
Each test carries its check id as `@Tag("RA-V1.1")` so `scripts/verify_spec.py <spec-dir> RA` can prove every check has a passing test and no test has a made-up id.

Unless a check says otherwise it uses these stand-ins:

| Stand-in for | What |
|---|---|
| Telegram | a local HTTP server that implements `sendMessage` and `getUpdates` (offset, timeout), records every request, and can fail, delay or send replies from chosen chat ids |
| Models | the scripted `LLMClient` of the earlier suites |
| Time | an injected `Clock` |
| Storage | a `@TempDir` run store; file triggers |
| The person | the test, posting replies to the fake server or calling `Answers.record` |

## V1: A question that waits (R1)

| # | Check |
|---|---|
| V1.1 | A run with a channel that reaches a `human_prompt` writes one pending record, sends one message, and exits suspended (HUMAN) with no thread left. |
| V1.2 | The same for a tool approval, a held rewind, a promotion approval and a `decide` question: each is one record, one message, one suspension. |
| V1.3 | After an answer is recorded and the run resumed, the step receives the answer and the fake server saw no second message. |
| V1.4 | Resuming with no answer suspends again and sends nothing. |
| V1.5 | The message carries the run name, the question text, a code, and the choices when the step has them. |
| V1.6 | With no channel, or `console`, the behaviour is byte-for-byte the existing one (the existing human-prompt tests pass unchanged). |
| V1.7 | `--ask-via telegram` without `--journal` stops at start with the reason. |
| V1.8 | Crash matrix: killing after the record, after the send, after the answer and after the resume trigger each gives, on re-run, one answer applied and at most a duplicate message with the same code. |

## V2: Answering (R2)

| # | Check |
|---|---|
| V2.1 | `weave answer` records the text, leaves one `ResumeRun` trigger, and `weave tick` resumes the run. |
| V2.2 | `weave questions` lists open questions with code, run, age and question; `--all` adds answered and expired; `--json` parses. |
| V2.3 | A second answer to the same code is refused and names who answered first and when. |
| V2.4 | A text matching no choice is not recorded; the reply lists the choices; the question stays open. A unique prefix (`app`) matches `approve`. |
| V2.5 | An accepted answer writes one audit line (code, who, time, text) and the step's journal entry holds the answer. |
| V2.6 | An unknown, expired or answered code changes nothing and says why. |
| V2.7 | A question answered at the console closes its pending record. With no channel configured, a run resumed after `weave answer` takes that answer and never asks the console. |

## V3: Telegram (R3)

| # | Check |
|---|---|
| V3.1 | The token appears in no file under the store, no audit line, no trace and no error text, including when the fake server returns an error that echoes the URL. |
| V3.2 | The message is sent with no `parse_mode`; a case value containing `*bold*`, a link and `<b>` arrives as typed. A 5000-character question is cut with the marker and the record holds the whole. |
| V3.3 | A reply from a chat id not on the allowlist is ignored, counted and not answered; so is a reply from a user id not on the allowlist inside an allowlisted group chat; an empty allowlist at start is an error, never "everyone". |
| V3.4 | Matching: by reply-to; by `K7F3QX approve` and `#k7f3q approve`; by the only open question; with two open questions and no code the sender gets the list and nothing is recorded. |
| V3.5 | An accepted answer is confirmed with `Recorded: approve for K7F3QX`; a reply not understood gets help. |
| V3.6 | Restarting the listener neither re-applies old replies nor misses new ones (offset kept; fault injected between recording and saving the offset gives one application, not two). |
| V3.8 | A reply dated before the question was asked is ignored and counted, answered with no note, and does not answer it even when it is the only open question; a reply dated after it does. |
| V3.7 | With the fake server failing, delayed or dropping the connection: the listener keeps running and backs off; a failed send leaves the record `unsent`, `questions` shows it, and the next tick sends it. |

## V4: Where nobody sits (R4)

| # | Check |
|---|---|
| V4.1 | `--ask-via` on `run`, `resume`, `tick` and `daemon` selects the channel and wins over `channel.json`. |
| V4.2 | `weave tick` polls once without waiting, records answers, then fires due triggers: one tick turns a posted reply into a resumed, completed run. |
| V4.3 | `weave daemon` answers within the poll interval of a reply (clock and server driven, no sleeps over a second). |
| V4.4 | Reminders: after `remind.every` the question is re-sent under the same code, at most `atMost` times. |
| V4.5 | Expiry: after `expire` the record closes, a resume trigger is left, the step receives the end-of-input answer, and the operator is told once. |
| V4.6 | `questions` and `answer` work with no channel configured. |
| V4.7 | `chats` maps `ask: support-lead` to its chats; an unmapped name goes to the default chat. |

## V5: Safety (R5)

| # | Check |
|---|---|
| V5.1 | An answer applies only from an allowlisted sender, for an open question; a hostile table of senders, codes, expired and answered states changes nothing it should not. |
| V5.2 | Codes: 25 bits or more, from `SecureRandom`, unique among open questions (a forced clash draws again). |
| V5.3 | For approval-kind questions, a bare reply, a reply-to link without the code, and the only-open-question shortcut all fail; the code in the reply succeeds. |
| V5.4 | A `watch` question, its reminder and its confirmation contain no proposal, reasoning or confidence text (hostile proposals with recognisable words). |
| V5.5 | Control characters and line breaks in questions and replies are neutralised; a reply like `; rm -rf /` or `../../x` is only ever text compared with the choices. |
| V5.6 | The secrets sweep: run V1–V4 with a recognisable fake token; `grep` every file in the store, the run directory, the audit log, captured logs and traces: no occurrence. |
| V5.7 | A channel with no token, or no allowlist, or no route to the host at start, fails the command and names what is missing. |

## V6: Other channels (R6)

| # | Check |
|---|---|
| V6.1 | The contract test class (send once, poll replies, confirm, failures) runs against `TelegramChannel`, `CommandChannel` and `ConsoleChannel` and passes for each. |
| V6.2 | The `command` channel pipes the question JSON to a script, and `weave answer` from that script resumes the run. |
| V6.3 | An architecture test fails if anything outside the Telegram class, its configuration and its tests mentions `telegram`. |

## V7: Documentation (R7)

| # | Check |
|---|---|
| V7.1 | Every `loom` and `bash` block in the new guide section is validated or run by the guide test; the setup steps work against the fake server. |
| V7.2 | The earned-autonomy section mentions answering from a chat; `weave --help` lists `answer` and `questions`; a test fails if either is missing. |
| V7.3 | No sample directory was added. |

## V8: Regression and quality

| # | Check |
|---|---|
| V8.1 | The whole existing suites pass unchanged; no existing test is removed or edited. *(gate-checked: G4)* |
| V8.2 | Coverage: the new package at least 85% lines; `Answers`, matching and the allowlist check at least 90% branches, enforced by JaCoCo. *(gate-checked: G8)* |
| V8.3 | Mutation pass: ignore the allowlist; accept an answer twice; match by the wrong code; drop the code requirement for approvals; put the proposal in a watch message; send again on every resume; advance the offset before recording; log the token; treat an empty allowlist as everyone. *(gate-checked: G3)* |

## Live checks (optional)

| # | Check |
|---|---|
| L1 | With a real bot, run a `decision` in `watch` on a small machine under `weave triggers install`; the question arrives on a phone, the answer comes back, and `weave autonomy status` counts the case. |
| L2 | The same under a Cloud Scheduler tick against a scale-to-zero service with a hosted Postgres. |

# Completion

Same shape as the earlier specs' gates.

| Gate | What | Pass rule |
|---|---|---|
| G0 | Clean tree; every task ticked except optional live ones | no unticked box |
| G0a | The earned-autonomy gates passed at a commit this builds on | its sign-off exists and names it |
| G1 | Clean build of the three modules three times (plain, plain, random order) | all succeed; the plain runs have equal counts |
| G2 | Traceability | script output clean |
| G3 | Sabotage: each V8.3 change made in the real source and a named test fails | every one detected |
| G4 | Regression against the commit before the work | zero |
| G5 | Real `weave` processes against a local fake Telegram server: a decision in `watch` asked through the channel, answered by posting a reply, resumed by `weave tick`; `kill -9` between send and answer, then resume, gives one answer; a stranger's reply is ignored; `weave answer` from a terminal; an expiry | transcripts as expected |
| G6 | The packaged JAR runs `check`, `questions` on an empty store, and `answer` on an unknown code | works with only the JAR |
| G7 | Docs validated; commands present in `--help` | clean |
| G8 | Coverage rules | met |
| G9 | Safety: V5, the secrets sweep and a security review of the diff | no hostile case succeeds; findings fixed or explained |
| G10 | Live L1–L2, optional | pass, or "not run: <reason>" |
