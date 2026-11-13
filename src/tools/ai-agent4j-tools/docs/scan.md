# `scan`

[← all tools](README.md)

Finds credentials and personal data in text, or in files, and says **where, never what**. Use it as a gate before something is
sent or published, after an agent has produced files, or to catch an agent that copied a key into its answer.

## From Java

```java
Tool scan = new ScanKind().create("Scan", Map.of("rules", "secrets, pii", "max_findings", "50"), Path.of("."));
scan.execute(Map.of("text", draft));   // {"rules_version": "scan-1", "count": 0, "findings": [], ...}
```

`create` takes the options as strings; `new ScanKind().check(options, baseDir)` returns what is wrong with them (or `null`).

## From a Loom script

```text
tool Scan   { use: scan }
tool Notify { use: webhook  url: env.SLACK_WEBHOOK }

workflow Publish(draft) {
    use Scan(text = draft) -> found
    alt (found.count > 0) { halt "The draft holds {found.count} secrets or personal details; not sent." }
    use Notify(text = draft)
}
```

## Options

| Option | Meaning |
|---|---|
| `root` | A directory, inside the script's, whose files `path` may name. Without it the tool takes text only |
| `rules` | `secrets`, `pii` or both (the default) |
| `pii` | The personal data looked for: any of `email`, `phone`, `ssn`, `credit_card`, `ip_address`, `url`. Default `email, phone, ssn, credit_card` |
| `max_bytes` | Largest text or file, default 1m, at most 16m; a larger file in a directory is skipped and counted |
| `max_files` | Most files read from a directory, default 1000, at most 20000 |
| `max_findings` | Most findings returned, default 100, at most 5000 (`count` is always the full number) |

The call takes `text`, or `path` (a file or a directory under `root`).

## The result

```json
{"rules_version": "scan-1", "count": 1, "truncated": false, "files": 3, "skipped": 0,
 "findings": [{"rule": "aws_access_key", "file": "deploy/.env", "line": 2, "start": 13, "end": 33}],
 "note": "each finding is a rule and a place; the matched text is never shown"}
```

A finding is a rule, the file (for a path), the line and the columns (from 1; `end` is one past the last character). **The
matched text appears nowhere**: not in the result, the journal, the trace or an error, so a secret it finds does not reach a
model's context. The rules are versioned (`rules_version`), so a finding can be reproduced and a change in rules is visible.

The secret rules are known shapes (`private_key`, `aws_access_key`, `github_token`, `slack_token`, `google_api_key`,
`api_key_sk`, `jwt`, `assigned_secret` for `password = ...` and the like) and `high_entropy` for long random-looking strings
(32 characters or more, mixing upper case, lower case and digits, so a hexadecimal hash is not flagged). Personal data is
found by the core `RegexPIIDetector`. A directory is read file by file, without following links; files that are not text are
skipped.

**A net, not a proof.** A clean result means nothing the rules know was found. False negatives exist: a secret in an unusual
shape, or personal data the patterns do not cover, passes. There is no `redact` action: `scan` is a gate.

## What the audit sees

Without `root`, a tool that reaches nothing; with it, one that reads the files under that directory. It changes nothing. The
file names in findings may have been chosen by a model, so the findings stay unchecked text; `count` is the host's own number.
