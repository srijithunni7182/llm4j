# Requirements Document

## Introduction

Loom can already pause a run that needs a person and resume it later, holding no thread. What it cannot do from the command line is *reach* the person,
or *hear back* from them. Today `weave run` always asks at a console: on a machine nobody is sitting at (a small server, a scheduled job, a daemon)
the prompt fails instead of waiting, and nothing in the `weave` command line records an answer given somewhere else.

That is the gap between "Loom runs unattended" and "Loom runs unattended and still asks me when it needs me". It matters most for
[earned autonomy](../loom-earned-autonomy/requirements.md), whose `watch` level is *a person answering every case*: the person has to be
answerable from a phone, an hour or a day later, not from a terminal that is open right now.

This spec adds **remote answers**: when a run needs a person and no console is attached, the question is sent to a **channel** (Telegram first),
the run pauses, the person replies in the chat, and the run carries on. Everything that asks a person goes through the same path: `human_prompt`,
tool approvals, `rewind ... side effects: ask first`, promotion approvals, and the `decide` question of earned autonomy.

| Idea | What it means |
|---|---|
| **Ask where the person is** | The question arrives as a chat message, with a short code and the choices to reply with |
| **Pause, don't fail** | With a channel configured, a run that needs a person suspends (no thread, no process) and resumes when the answer is recorded |
| **One way to answer** | A reply in the chat, `weave answer` at a terminal and the console prompt all record the same thing, once |
| **Safe by construction** | Only people on an allowlist can answer; an answer applies once, to the question it names; no secret leaves the machine |

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** A channel that is configured but cannot send or listen is an error that says so, never a run that waits forever unnoticed.
- **Existing behaviour is unchanged.** With no channel configured the console prompt works exactly as before.
- **Blind measurement is preserved.** What is sent for a `watch` case is exactly the blind question the decide statement already builds; the channel adds nothing to it.
- **Secrets** (the bot token) come only from the environment, appear in no journal, ledger, audit line, trace or error message.

**Out of scope** (each a follow-up, not a gap in this one):

- WhatsApp. Its Business API needs an approved account and message templates; the channel interface (design §2) is what a WhatsApp channel would implement.
- Rich replies: buttons, attachments, voice answers, multi-step conversations. A reply is one line of text.
- Several people voting on one question. One answer wins; the first valid one from the allowlist.
- A hosted service or web page for answering. This is the command line and the channel.

## Glossary

- **Question**: one request for a person's answer, identified by a short **code** (for example `K7F3Q`), bound to one run and one step.
- **Channel**: where questions are sent and replies come from (`telegram`, `command`, `console`).
- **Pending record**: the durable file in the run store that holds a question, whether it was sent, and its answer once given.
- **Listener**: the part that reads replies from the channel and records them as answers.
- **Allowlist**: the chat ids whose replies are accepted.

## Requirements

### Requirement 1: A question that waits

**User Story:** As someone running Loom on a machine I am not sitting at, I want a run that needs me to pause and ask me, so that it neither fails nor holds a process.

#### Acceptance Criteria

1. WHEN a run with a journal needs a person and a channel other than `console` is configured for the run store, THE runtime SHALL write a pending record, send the question once through the channel, and suspend the run (`RunSuspended`, reason HUMAN) so that the process can exit.
2. THE same path SHALL serve every place that asks a person: `human_prompt`, tool approvals, a rewind held by `ask first`, promotion approvals, and the `decide` question.
3. WHEN the run is resumed and an answer is recorded, THE runtime SHALL return it to the step that asked, exactly as the console would have, and SHALL NOT send the question again.
4. WHEN the run is resumed and no answer is recorded yet, THE runtime SHALL suspend again without sending anything new (a reminder is the only exception, R4.3).
5. THE question SHALL carry: the run name, the question text as the step built it, a short code, and, when the step has a fixed set of choices, the words to reply with.
6. WITH no channel configured, or `console` configured, THE behaviour SHALL be exactly what it is today.
7. A run without a journal cannot suspend: THE runtime SHALL say so at start (`--ask-via` needs `--journal`) instead of failing at the first question.

### Requirement 2: Answering

**User Story:** As the person asked, I want to answer from wherever I am, so that the run continues without my opening a terminal.

#### Acceptance Criteria

1. THE command line SHALL have `weave answer <store> <code> <text…>`, which records the text as the answer to the question with that code, leaves a resume trigger for the run (so `weave tick` or `weave daemon` carries on), and prints what it did.
2. THE command line SHALL have `weave questions <store>`, which lists open questions with code, run, how long they have waited, and the question; `--all` includes answered and expired ones.
3. AN answer SHALL be recorded once: a second answer to the same code is refused and says who answered first and when.
4. WHERE the step has fixed choices, THE answer SHALL be matched to a choice the way the console already matches it (exactly, any case, or as the unique start of one). An answer that matches none is not recorded: the person is told the choices and the question stays open.
5. EVERY recorded answer SHALL be written to the run's audit log with the code, the person (channel and chat id, or the operating-system user), the time, and the text, and SHALL appear in the run journal as the step's answer.
6. AN answer to an unknown, expired or already-answered code SHALL change nothing and say why.
7. THE console path (`ConsoleHumanInterface`) SHALL keep working, and a question answered at the console SHALL close any pending record for it.

### Requirement 3: The Telegram channel

**User Story:** As someone who lives in a chat app, I want questions and answers to go through a Telegram bot, so that I can answer from my phone.

#### Acceptance Criteria

1. THE Telegram channel SHALL use the Bot API over HTTPS with the token from the environment variable named by the channel configuration (default `TELEGRAM_BOT_TOKEN`); the token SHALL never be written to a file, journal, ledger, audit line, trace or error message.
2. A question SHALL be sent as plain text with no markup mode, so nothing in a case's data can be interpreted as formatting or links the person might tap; text longer than the message limit SHALL be cut with a visible "(cut)" marker, and the full text SHALL stay in the pending record.
3. A reply SHALL be accepted only from a chat id on the allowlist (`TELEGRAM_CHAT_IDS`, comma separated, or the channel file); anything else SHALL be ignored, counted, and noted once in the log, never answered.
4. A reply SHALL be matched to a question by, in order: the Telegram "reply to" link to the question message; a leading code (`K7F3Q approve`, `#K7F3Q approve`); the only open question, when there is exactly one. Anything else gets a short help message that lists the open questions, and changes nothing.
5. THE listener SHALL confirm an accepted answer with a short message (`Recorded: approve for K7F3Q`) and say when a reply was not understood.
6. THE listener SHALL remember how far it has read (the Telegram update offset) in the run store, so a restart neither re-applies old replies nor misses new ones.
7. WHEN the network or the Bot API fails, THE listener SHALL retry with a growing delay and keep running; a send that fails SHALL leave the pending record `unsent`, retried on the next tick, and `weave questions` SHALL show it.

### Requirement 4: Running it where nobody sits

**User Story:** As an operator, I want one setup that works on a small always-on machine, a scheduled job or a scale-to-zero service, so that I am not tied to one way of hosting.

#### Acceptance Criteria

1. `weave run`, `weave resume` and `weave tick` SHALL accept `--ask-via <channel>` (and the store may carry a default in `<store>/channel.json`); the command line SHALL win over the file.
2. `weave tick <store>` SHALL, when a channel is configured, poll the channel once (no long wait), record any answers, and then fire due triggers, so a cron line or a cloud scheduler is enough: no process waits in between.
3. `weave daemon <store>` SHALL, when a channel is configured, listen continuously (long polling) so an answer is acted on within seconds.
4. THE operator MAY configure reminders (`remind every 6h, at most 3`) and an expiry (`expire after 3 days`) in the channel file. A reminder repeats the question under the same code; an expired question is closed, the run is resumed with a recorded "no answer" that the asking step handles as the console's end-of-input is handled today, and the operator is told.
5. `weave questions` and `weave answer` SHALL work with no channel configured, so the same store can be answered from a terminal on the host.
6. THE channel file SHALL map the names a script asks (`ask: support-lead`) to chat ids, so different people can be asked different things; a name with no mapping goes to the default chat.

### Requirement 5: Safety

**User Story:** As someone giving a bot the power to approve things, I want it hard to answer on my behalf, so that a chat message cannot move an agent up a ladder or approve a tool call unless I sent it.

#### Acceptance Criteria

1. AN answer SHALL apply only if the sender is on the allowlist, the code names an open question, and the question has not expired or been answered.
2. CODES SHALL be unguessable enough that one cannot be hit by chance (at least 25 bits, from a secure random source) and SHALL be unique among the store's open questions.
3. AN approval-type question (a tool approval, a promotion, a rewind that repeats a performed effect) SHALL require the code in the reply: the "only open question" and "reply to" shortcuts SHALL NOT answer those; and the message SHALL say what is being approved in the words the console would use.
4. THE message for a `watch` case SHALL contain only what the blind question contains: no proposal, reasoning or confidence, for the question or for any reminder or confirmation.
5. THE text of a question, a reply or a confirmation SHALL be neutralised for control characters; a reply is never executed, interpreted as a command, or used as a path.
6. THE pending records, the offset, the audit lines and the log SHALL never contain the token; a test SHALL search for it.
7. WHEN a channel is configured but unusable (no token, no allowlist, no network at start), THE command SHALL fail at start and say what is missing; a missing allowlist SHALL never mean "accept everyone".

### Requirement 6: Any other channel

**User Story:** As a maintainer, I want to add Slack, WhatsApp or e-mail later without touching the runtime, so that the channel is a small, replaceable piece.

#### Acceptance Criteria

1. THE channel SHALL be a small interface with three operations (send a question, poll for replies, confirm); the runtime, the pending records and the commands SHALL not mention Telegram.
2. A `command` channel SHALL exist: the question is piped to a configured program (for a bridge to anything), and replies are read from `weave answer` calls the bridge makes.
3. THE same contract tests SHALL run against every channel (Telegram against a local fake server, `command` against a script, `console`).

### Requirement 7: Documentation and tooling

#### Acceptance Criteria

1. THE guide SHALL gain a section "Answering from your phone" (setup in five steps: create a bot, set two environment variables, `weave triggers install`, run, answer), validated like the rest of the guide.
2. THE earned-autonomy section SHALL say that `watch` works from a chat, and `weave --help` SHALL list `answer` and `questions`.
3. NO sample workflow directory SHALL be added.
