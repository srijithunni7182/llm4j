# D2. Security decisions, before anything runs for real

**Goal:** the workflow's risky combinations are decided by the user, in writing, before it is watched or run for real; keys are kept where they should be.

## 1. The static audit

```
weave audit flow.loom --fail-on medium
weave audit flow.loom --loot tools.loot       # when your own tools declare what they reach
```

It reads the script and says, for each agent, whether it reads untrusted content, reaches private data and can send or act (the **lethal trifecta**), which effects have no approval, whether there is a run budget and a PII guard, and which tools are opaque. Read every finding to the user. A finding is fixed or explained; it is not silenced.

## 2. Tools say what they reach

A tool whose author does not say what it reaches is assumed to touch all three legs. Declare it once, where the audit and a watched run both read it:

```text
BannedPhraseChecker = shop.BannedPhraseChecker
BannedPhraseChecker.reach = reads
```

`reach` is `none`, `reads`, `fetches` (content from outside), `writes` or `sends`. It is the author's word; the audit lists it for someone to confirm against the code.

## 3. The lethal trifecta: the user decides, in writing

For each agent that holds all three legs, offer these, and let the user choose:

- **Split it**: one agent reads, another acts, and `approve:` is on the one that acts.
- **Accept it, in writing**, in the rules file beside the script (`flow.inspect`):

```text
Support {
    trifecta: accepted "customer support: reads the page, looks up the account, replies by mail; reviewed by Ravi 2026-10-14"
}
```

The reason is required and names who looked and when; it is shown on the page. An accepted trifecta is information, not an alarm, and from then on a suspected injection on that agent is **high**. Never accept for `*` unless the user says it in so many words, and tell them what it hides. **You do not accept it on their behalf.** Check the file without running anything: `weave observe rules flow.loom`.

## 4. Keys

- Provider keys: in the environment or in an encrypted store (`weave secrets create`, `weave secrets set NAME --stdin`), never in a script, a `.env` the user did not mean to commit, or chat. `weave secrets set` never prints or takes a value from arguments.
- A secret can be limited to hosts (`--allow-host api.provider.com`) so a hijacked tool cannot send it elsewhere.
- The Fabric admin password and a process's Fabric client key are separate secrets; the scripts handle both (chapters 5 and 6).

## 5. What may leave the machine

`--observe-detail`: `full`, `truncated` (the default: the first 500 characters), or `metadata` (no text at all). Ask the user which; a workflow that handles personal data usually wants `metadata` on a shared Fabric, plus a `guard { pii }` on the agents (the audit warns when private data has none).

## 6. Who may change a running workflow

Pause, suspend and stop need an admin sign-in; with none set the page is read-only. On Fabric the admin password you set at the first start is the one. On an embedded page, the admin password is a *hash* kept in the app's secret store (`LOOM_INSPECT_NEW_PASSWORD='...' weave observe hash-password`, then `weave secrets set LOOM_INSPECT_ADMIN_PASSWORD --stdin`); ask the user if they want controls at all.

## Gate

- `weave audit --fail-on medium` is clean, or every finding has an explanation the user has seen.
- For each agent with the trifecta: split, or accepted in writing by the user with a reason.
- Tools declare `reach`; keys are in a store or the environment; the detail level is chosen; the user knows who can pause or stop a run.
