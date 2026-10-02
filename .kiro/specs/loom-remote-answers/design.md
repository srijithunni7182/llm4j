# Design Document

## Overview

Remote answers add one `HumanInterface` that does not block (`ChannelHumanInterface`), a small `Channel` interface with a Telegram and a `command` implementation,
durable *pending records* in the run store, and three commands (`weave answer`, `weave questions`, `--ask-via`). Everything that asks a person already goes
through `HumanInterface.promptHuman(stepId, question)`, which is documented to "throw `RunSuspended` to pause the run without holding a thread". Nothing in the
executor, the journal or the decide statement changes.

```
 step needs a person ──▶ ChannelHumanInterface.promptHuman(key, question)
                              │
          pending record for (run, key)? ──no──▶ create record (code) ─▶ Channel.send ─▶ mark sent ─▶ throw RunSuspended
                              │ yes
                  answered? ──yes──▶ return the answer (record closed, audited)
                              │ no
                          throw RunSuspended              (nothing sent again)

 Telegram ──▶ Listener.poll ──▶ allowlist ─▶ match (reply-to / code / only one open) ─▶ Answers.record(code, text, who)
 terminal ──▶ weave answer ───────────────────────────────────────────────────────────▶ Answers.record(code, text, who)
                                                                                           │
                                                                       pending record gets the answer; a ResumeRun trigger is left
                                                                                           ▼
                                                                 weave tick / daemon resumes the run ─▶ promptHuman returns the answer
```

## 1. What is stored

### 1.1 The pending record

`<store>/channel/questions/<code>.json`, written atomically (temp file then move; a lock file serialises `record`):

```json
{ "code": "K7F3Q", "run": "/srv/runs/triage-42", "runId": "…", "step": "Triage/s0#decide-ask",
  "question": "support-lead, please decide Refund (approve / reject / escalate)\namount = 50",
  "choices": ["approve", "reject", "escalate"], "kind": "decide|approval|prompt",
  "to": "support-lead", "state": "open|answered|expired",
  "sent": { "channel": "telegram", "chat": 123456, "message": 981, "at": "…", "reminders": 0 },
  "answer": { "text": "approve", "by": "telegram:123456", "at": "…" } }
```

`(run, step)` is unique: asking again for the same step finds the same record, which is what makes a resumed run not re-send (R1.3, R1.4). The question text is stored as built; a `watch` question is the blind
one, so the record holds no proposal. Records are kept after answering (for `--all` and audit) and removed by the existing retention sweep.

### 1.2 Codes

Five characters from a 32-letter alphabet without `I`, `L`, `O`, `0`, `1` (25 bits) from `SecureRandom`, checked against the store's records; a clash draws again. Replies are matched case-insensitively.

### 1.3 Channel configuration

`<store>/channel.json` (optional; `--ask-via` overrides `channel`):

```json
{ "channel": "telegram",
  "tokenEnv": "TELEGRAM_BOT_TOKEN",
  "chats": { "default": [123456], "support-lead": [123456, 777888] },
  "remind": { "every": "6h", "atMost": 3 },
  "expire": "3d",
  "command": null }
```

The token is never in the file: only the *name* of the environment variable. `TELEGRAM_CHAT_IDS` is read as the default allowlist when the file does not name chats.

## 2. The channel interface

```java
public interface Channel {
    /** Sends a question; returns where it went (chat, message id) so a reply-to link can be matched. */
    Sent send(Question q) throws IOException;
    /** Replies since the last call. {@code wait} is how long to hold the connection open (zero from tick). */
    List<Reply> poll(Duration wait) throws IOException;
    /** A short note back to the sender (confirmation, help). */
    void tell(String chat, String text) throws IOException;
}
```

`Question` (code, text, choices, to), `Sent` (chat, messageRef), `Reply` (chat, text, replyToRef). The runtime, `Answers` and the commands know only this; the Telegram class is the only
place that names Telegram.

- **`TelegramChannel`**: `sendMessage`, `getUpdates` with `offset` and `timeout`. JDK `HttpClient`; no new dependency. The base URL is injectable so tests use a local fake. Plain text, no `parse_mode`. 4096-character limit: the sent text is cut with `…(cut)`.
  The offset is kept in `<store>/channel/offset.json` and advanced only after the replies it covers are recorded.
- **`CommandChannel`**: `send` runs the configured program with the question as JSON on stdin; `poll` returns nothing (replies come as `weave answer` calls). For a bridge to anything else.
- **`ConsoleChannel`**: today's behaviour; present so the contract tests cover it.

## 3. The interface the runtime sees

`ChannelHumanInterface implements HumanInterface` (in `cli`, built where `Runs` builds the console one, when a channel is configured):

```
promptHuman(key, text):
    rec = pending.find(run, key)
    if rec == null:  rec = pending.create(run, key, text, choices?, kind, to)         // code drawn here
    if rec.answered: pending.close(rec); audit; return rec.answer.text
    if !rec.sent:    channel.send(...)  →  pending.markSent(...)   ; on failure keep `unsent` and still suspend
    throw new RunSuspended(key, text)
```

`choices` and `kind` come from the caller. The interface gains a default method `promptHuman(stepId, message, Hints hints)` that delegates to the two-argument form, so the existing callers need no change; the decide
question, the approval gate and the rewind prompt pass hints (their choices and their kind) and the others pass none. `kind` decides whether shortcuts are allowed (R5.3).

At-least-once: a crash between `send` and `markSent` can send the question twice; both messages carry the same code, and an answer applies once.

## 4. Recording an answer (`Answers`)

```
record(code, text, who):
    lock
    rec = pending.get(code)            → unknown → "no such question"
    rec.state != open                  → say who answered first / that it expired
    choices != null: match(text)       → none → "choose one of …", nothing recorded
    rec.answer = {text, by, at}; rec.state = answered; write atomically
    audit(run, code, by, text)
    triggers.add(ResumeRun(rec.runId), now)          // the existing AT trigger; tick or daemon runs it
    unlock
```

The answer is *not* written into the run journal by `record`: the resumed run asks again, `ChannelHumanInterface` returns it, and the step journals it the way it journals any answer (the decide ask under `#decide-ask`, an approval under its key). That keeps one writer of journal entries and keeps
a fork or replay of the run unaffected.

## 5. The listener

`Listener.pollOnce(wait)`:

1. `channel.poll(wait)`; for each reply: not on the allowlist → ignore and count; else match: `replyToRef` to a record's `sent.message`; else a leading code token; else the only open question (not for `approval` kinds); else tell the sender the open questions.
2. `Answers.record(...)`; `tell("Recorded: approve for K7F3Q")` or the refusal reason.
3. Reminders and expiry: records open longer than `remind.every` get the question re-sent (same code, `reminders+1`); older than `expire` are closed as `expired`, a ResumeRun trigger is left, and `ChannelHumanInterface` returns the end-of-input answer the console produces (an empty string), which the asking step already handles.
4. Persist the offset.

`weave tick` calls `pollOnce(0)` before firing triggers; `weave daemon` calls `pollOnce(30s)` in a loop beside its tick loop. Both retry a failing channel with a growing delay (capped) and log once per outage.

## 6. Commands

| Command | Does |
|---|---|
| `weave answer <store> <code> <text…> [--by name]` | `Answers.record` as the operating-system user (or `--by`); prints the result |
| `weave questions <store> [--all] [--json]` | lists records: code, run, state, age, question (one line, cut) |
| `--ask-via <channel>` on `run`, `resume`, `tick`, `daemon` | the channel for this command; `console` forces today's behaviour |

`weave run` with a channel and no `--journal` stops at start with the reason (R1.7).

## 7. Blindness and neutralising

The text sent is the `question` string the decide statement already builds for the console (blind at `watch`); `ChannelHumanInterface` adds nothing to it and the confirmation never repeats more than the code and the choice. All text sent or recorded passes `RunTravel.neutralise`.
A test sends every level's question through a fake channel and fails if a proposal's text appears.

## 8. Failure behaviour

| Fault | Result |
|---|---|
| No token or allowlist at start | the command fails and names what is missing |
| Send fails | record `unsent`, run suspends, retried at the next tick; `questions` shows it |
| Poll fails | backoff and retry; never exits; one log line per outage |
| Reply from a stranger | ignored and counted |
| Two answers at once | the lock admits one; the other is told who was first |
| Kill between send and markSent | the question may arrive twice, same code; answer applies once |
| Answer arrives after expiry | refused with the reason; the run already carried on |
| Store on a read-only disk | the command fails at start |

## 9. Compatibility

No journal or ledger format changes. `HumanInterface` gains a default method. `WeaveEnv` builds a channel interface only when asked. Scripts are unchanged.

## 10. Deviations to expect

None yet; recorded in the sign-off if any arise.
