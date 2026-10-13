# Requirements Document

## Introduction

A web application logs a user in (OAuth, say) and runs a Loom workflow for them. A tool in that workflow, an OpenAPI tool, has to call the user's API **as
that user**. Today a tool's credential is read once when the tool is created, so one executor can only act as one identity, and an access token that expires
in the middle of a long or resumed run cannot be replaced.

This spec adds **invoker credentials**: a script says `auth_value: invoker.token`, the application supplies an `InvokerCredentials` object with the run, and the
tool asks it for the value on **every request**, so the application can refresh the token with the user's refresh token. Design: [design.md](design.md).
Checks: [verification.md](verification.md).

The standing rules from earlier Loom specs still apply:

- **Existing behaviour is unchanged.** A script that does not use `invoker.` runs as before, with the same agent identity.
- **Secrets never leave the run.** A credential appears in no prompt, journal, trace, audit line, result, error or log.
- **Nothing is silently ignored.** A run that needs credentials it was not given stops before its first step and says which.

**Out of scope** (each a follow-up, not a gap in this one):

- Making `HarnessExecutor` re-entrant (several outermost runs at once on one executor). It refuses a second one; see the design.
- `invoker.NAME` on the `http` tool, on `header.*` options and on providers.
- The OAuth flow itself: login, storage and refresh of tokens belong to the application.

## Requirements

### Requirement 1: A script says who the tool acts as

**User story:** As a script author, I want to write that a tool uses the credential of whoever runs the workflow, so that the script does not name any one person's key.

1. `auth_value: invoker.NAME` is accepted for `openapi`, with `NAME` a letter followed by letters, digits or `_`.
2. It is refused with a clear message wherever it cannot work: other options and kinds, `header.*`, model providers.
3. A literal credential is still refused, and the message offers `invoker.NAME` where it works.
4. `weave check` says which credentials a script takes from the invoker; `weave audit` reports a tool that acts as the person (LA17).
5. Existing scripts keep their agent identity.

### Requirement 2: The host supplies and refreshes the credential

**User story:** As an application developer, I want to hand the run an object that returns the user's current token, so that a refreshed token is used at once.

1. The value is asked for on every request that needs it.
2. A server answer of 401 makes the tool tell the host, ask again and repeat the request once.
3. A credential the host does not have, or cannot get, is an observation for the agent, not a crash, and nothing is sent.
4. `weave run --invoker-env NAME=VARIABLE` supplies a credential from the environment.

### Requirement 3: The credential belongs to the run

**User story:** As an operator, I want two users' runs never to see each other's credential.

1. Parallel branches, broadcasts, timed steps and called workflows act as the person the run is for.
2. A worker thread keeps nothing from a run once it has finished with it.
3. An executor runs one outermost workflow at a time and refuses another.

### Requirement 4: The credential goes only where it should

**User story:** As a security reviewer, I want an invoker credential sent only to the API it was meant for.

1. A run that needs credentials it was not given stops before its first step, naming them.
2. The credential is sent only over https, or to this machine, and only to the host of the API description; the agent chooses a path, never a host.
3. A redirect is not followed, so the credential is not sent on to another host.

### Requirement 5: It is never written down

**User story:** As a security reviewer, I want to be sure a token does not end up in a log or a journal.

1. The token is in no prompt, trace event, audit line, journal, log line, result or error, even when the API repeats it.
2. A run that paused is resumed with a new `InvokerCredentials`; nothing is stored for it.

### Requirement 6: Nothing else changes

1. Existing scripts, the old `executeWorkflow` overload and the whole existing test suites behave as before.
2. A scheduled or triggered run has no invoker and says so; the command line can supply one.

### Requirement 7: It is documented

1. The guide, prompt, wiki and indexes explain it, and every example in them loads.
