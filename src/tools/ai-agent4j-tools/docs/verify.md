# `verify`

[← all tools](README.md)

Turns the result of a check into one record a workflow can rely on: a JUnit XML test report, or a JSON document checked against
a JSON Schema. Use it after a program has run tests, to validate a model's structured output, or to give an evaluation a typed
result to score instead of text. It never runs a program.

## From Java

```java
Tool check = new VerifyKind().create("Check", Map.of("root", "out", "max_items", "100"), Path.of("."));
check.execute(Map.of("format", "junit", "source", "test-report.xml"));
```

`create` takes the options as strings; `new VerifyKind().check(options, baseDir)` returns what is wrong with them (or `null`).

## From a Loom script

```text
tool Check { use: verify  root: "out" }

workflow Release() {
    use Check(format = "junit", source = "test-report.xml") -> result
    alt (result.failed > 0) { halt "{result.failed} tests failed" }
}
```

## Options

| Option | Meaning |
|---|---|
| `root` | A directory, inside the script's, that `source` and `schema` files are read from. Without it the tool takes text only and reads no file |
| `max_bytes` | Largest report, schema or document, default 4m, at most 32m |
| `max_items` | Most items kept in the result, default 200, at most 5000 (`items_truncated` says when there were more) |

## Arguments

| `format` | Arguments |
|---|---|
| `junit` | `report` (the XML), or `source` (a file under `root`) |
| `schema` | `schema_text`, or `schema` (a file); and `document` (the JSON), or `source` (a file) |

## The record

```json
{"format": "junit", "source": "test-report.xml", "status": "failed", "total": 4, "passed": 1, "failed": 2,
 "errors": 1, "skipped": 1, "items": [{"name": "OrdersTest.addsALine", "status": "passed"}, ...], "items_truncated": false}
```

- `status` is `passed`, `failed` or `empty` (a report with no tests: `total` is 0, and the script decides what that means).
- `failed` counts every item that neither passed nor was skipped; JUnit errors are counted in it and also in `errors`.
- **A report or document that cannot be read is a failed check, never a pass:** `status` is `failed`, `failed` is 1 and
  `error` says why. So a gate on `failed > 0` never lets through what it could not read.
- For a schema, `total` is 1 (one document), and each place the document does not fit is an item named by its JSON Pointer
  (`/lines/0/qty`) with a message.
- JUnit counts are taken test by test; the report's own `tests=` attributes are not trusted. A report with a `DOCTYPE` is
  refused, so no entity is ever read.
- The schema keywords checked are `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`,
  `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems` and `maxItems`; `title`,
  `description` and similar are read and ignored. **A schema with any other keyword (`pattern`, `$ref`, `oneOf`, `format`, ...)
  is refused as a whole**, because a check that skipped a rule would pass what it never looked at.

**What it does not say:** that a requirement was met. It says an artifact passed a check, and the check is only as good as
whoever wrote it, so keep the tests and the schema where the agent cannot edit them.

## What the audit sees

Without `root`, a tool that reaches nothing; with it, one that reads the files under that directory. It changes nothing. The
counts and the status are data the host parsed. **The item names, messages and `error` came from the report, which a model's
code may have written, so they stay unchecked model text**: a test called "ignore your instructions" cannot leave the workflow
as if the workflow had written it.
