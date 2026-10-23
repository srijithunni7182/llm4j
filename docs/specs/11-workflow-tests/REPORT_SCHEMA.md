# `weave test --format json`: report schema (version 1)

One JSON object on standard output, followed by a newline. Fields are only ever added within a version; a change that removes or renames one raises `version`.

```json
{
  "version": 1,
  "script": "triage.loom",
  "summary": { "passed": 2, "failed": 1, "skipped": 0, "millis": 41, "seed": 17 },
  "coverage": {
    "covered": 8, "total": 10, "percent": 80,
    "uncovered": [
      { "workflow": "Main", "node": "n7", "line": 19, "label": "note",
        "toReach": "make the condition of \"intent.kind==order_status?\" false" }
    ]
  },
  "cases": [
    { "file": "tests/triage.test.yaml", "name": "refunds are escalated", "passed": false,
      "millis": 3, "tags": ["fast"], "failures": ["calls.Escalator: expected 1, got 0"], "notes": [] }
  ]
}
```

| Field | Meaning |
|---|---|
| `version` | the schema version (1) |
| `script` | the script the cases ran against |
| `summary.seed` | present only with `--shuffle`: repeat the order with `--seed` |
| `coverage` | present only with `--coverage` or `--min-coverage`; `percent` is whole percent of graph nodes reached; `uncovered[].node` is the id `weave graph` and `weave test --steps` use |
| `cases[].failures` | every difference, one text each (expected, actual, step); empty when the case passed |
| `cases[].notes` | what `--lenient` answered for an unscripted call; empty in strict mode |

Exit status: 0 all passed (and coverage at least `--min-coverage`), 1 a case failed or coverage was too low, 2 the files or the script could not be run.
