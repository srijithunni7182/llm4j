# `compute`

[← all tools](README.md)

Exact arithmetic, dates, a value out of JSON and statistics over CSV, computed from the arguments alone. Use it wherever a
workflow would otherwise ask a model to add up, count or reshape data: models are poor at arithmetic, and a tool that reaches
nothing has nothing to audit.

## From Java

```java
Tool calc = new ComputeKind().create("Calc", Map.of("max_rows", "50000"), Path.of("."));
calc.execute(Map.of("action", "calc", "expression", "(12.50 + 3) * 1.2"));   // {"value":"18.600","rounded":false}
```

`create` takes the options as strings. Before building, `new ComputeKind().check(options, baseDir)` returns what is wrong with
them (or `null`). The tool returns a JSON object; a refusal comes back as text starting `Error:`.

## From a Loom script

```text
tool Calc { use: compute }

workflow Invoice(net) {
    use Calc(action = "calc", expression = "{net} * 1.18") -> gross
    return gross.value
}
```

## Options

| Option | Meaning |
|---|---|
| `max_bytes` | Largest `document` or `text` argument, default 1m, at most 8m |
| `max_rows` | Largest CSV, in rows after the header, default 100000, at most 1000000 |

## Actions

| `action` | Arguments | Result |
|---|---|---|
| `calc` | `expression`: numbers, `+ - * / %`, parentheses, `round(x)`, `round(x, places)`, `min(...)`, `max(...)`, `abs(x)` | `{"value": "0.3", "rounded": false}` |
| `date` | `op`: `today`; `add` with `date` and any of `days`, `months`, `years`; `between` with `date` and `to`; `weekday` with `date`. Dates are `yyyy-MM-dd` | `{"value": "2026-11-20"}`, or `{"days": 12}` for `between` |
| `json` | `document` (JSON text), `path` such as `order.lines[0].sku` (the whole document when left out), `op`: `get` (default) or `keys` | `{"value": ...}` or `{"keys": [...]}` |
| `csv` | `text` with a header row, `column`, `op`: `count`, `sum`, `mean`, `min` or `max`, optional `group_by` (a column) | `{"value": "16.75", "rows": 4, "skipped": 1}`, or `{"groups": {...}, "rows": 4, "truncated": false}` |

- **Exact:** `calc` works in decimals, never binary floating point, so `0.1 + 0.2` is `0.3` and `1.50 * 2` is `3.00`. A division
  that does not end (`1 / 3`) is rounded to 34 significant digits and says `"rounded": true`.
- **Bounded:** an expression of at most 1000 characters, nested at most 32 deep, numbers of at most 100 digits, results of at
  most 400 digits; a date moved by at most 1000000 days, months or years; JSON nested at most 64 deep; at most 1000 groups
  (`truncated` says when there were more). Division by zero, a bad date, a path with nothing at it and anything over a limit
  are refused with the reason.
- **Deterministic:** `today` reads the run's clock, so a test with a fixed clock gets a fixed date, and a resumed run replays
  the same answer.
- **No regular expressions and no formula language:** a `sandbox` runs a real script for anything larger.

## What the audit sees

`weave audit` sees a tool that reaches nothing and changes nothing (`computes from its arguments only`); it needs no approval.
Because it computes only from its arguments, a value it returns carries their origin: a number picked out of a model's answer
is still the model's text when it leaves the workflow.
