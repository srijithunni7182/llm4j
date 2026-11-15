# Time to the first safe call

How long does it take to connect a system to a workflow so that **the first real call is safe**: its effect declared by a person,
its credential only a reference, its isolation chosen, its audit read? This page is the benchmark of phase 36 ([D17](../specs/36-integrations/DECISIONS.md)):
five systems, one harness, the same measures, so that "easy" is a number that can be improved.

## What is measured

For each system, `weave connect` is run non-interactively and the result is checked:

| Measure | Meaning |
|---|---|
| Manual edits after `connect` | Edits a person had to make to the written script beyond approving it. The goal is none |
| Approvals | How many times `connect` stopped to ask (the goal: one, for the whole change) |
| `weave check` | Whether the script it wrote passes |
| Audit findings | The number and the highest severity of the audit's findings on the **new** declarations |
| Samples | For a model-assisted draft: whether the draft answered the person's own samples |
| First real call | Whether the first call to the system worked |
| Wall-clock time and model cost | For the real runs only |

## Two kinds of run

**1. The repeatable run, in the default build** (`ConnectBenchmarkTest`, with every other test). It uses recorded fixtures, so it needs no network, no key and
no container runtime, and it runs on every build. What stands in for the real thing is named in each row.

**2. The real run, by hand, before a release.** The same harness against the real systems, with a real model for the two model-assisted rows. Its
results are published below when they exist, with the method, the versions and the date.

## The five systems

| # | System | Default build (recorded fixtures) | Real run |
|---|---|---|---|
| 1 | A REST API with a published OpenAPI description, reached with a bearer token the host supplies | `connect openapi --read-only` on the **recorded** Swagger Petstore description (Apache-2.0): one approval, the script passes `weave check`, no finding above medium | **not run** (needs a hosted or containerised service and a token) |
| 2 | A local MCP server over stdio | `connect mcp` on the testkit's stdio server (a real process, validated against the official schema): one approval, `isolation: process`, effects and a pin written, the listing is the first call and it worked | **not run** (the reference server in a container: needs a container runtime and the package) |
| 3 | A remote MCP server over Streamable HTTP | **not in the default build:** the protocol is covered by `McpHttpTest` and `McpRemoteRuntimeTest` against a schema-validated fake; `connect mcp` over https needs a TLS server | **not run** (a public or containerised server) |
| 4 | A command-line tool, for `connect command` | the **real `--help` of GNU `wc`**, a **scripted model** that gives a fixed draft, and the person's sample run on the real `wc`: one approval, passes `weave check`, the sample passes, the first real call succeeded | **not run** (a real model: its cost, its repairs and where it fails are the point of this row) |
| 5 | A system with only a documentation page, for `connect http` | a **hand-written** documentation excerpt and a **scripted model**: one approval, passes `weave check`, `methods` and `allow_paths` as the page shows, the credential written as `invoker.token` | **not run** (a real documentation page and a real model) |

The fixtures are in `src/loom/ai-agent4j-loom/src/test/resources/benchmark` (rows 4 and 5) and
`src/tools/extras/ai-agent4j-tools-openapi/src/test/resources/fixtures/petstore3` (row 1), each with a `METADATA.json` that says what it is, where it came from,
when, under which licence and what was removed; a test checks that and runs the `scan` tool over every file. **Rows 4 and 5 do not measure a model:** the
scripted model gives the same draft every time, so they show that the gates, the approval and the write work, not that a model drafts well.

## Reading the result

A row that passes in the default build says the **mechanism** works on a recording. It does not say a person gets to a safe call in so many minutes,
and it does not say a real model drafts a good adapter. Those are the real runs, and until they are made the table has no times in it on purpose: a number
from a fake would be a claim without evidence. Run them with:

```bash
mvn -B -pl src/loom/ai-agent4j-loom -Dtest=ConnectBenchmarkTest test     # the repeatable rows
weave connect openapi <the real description> --read-only --write main.loom --yes        # time it, then run weave check and weave audit
weave connect command <tool> --help-file help.txt --image <image> --workspace-tool <name> --samples samples.json --write main.loom --model <model>
```

Record, for each real run: the date, the versions (llm4j, the system, the model), the number of manual edits, the audit's findings, whether the first call
worked, the wall-clock time, the number of approvals and, for the model-assisted rows, the tokens and the cost. Re-run on every release.
