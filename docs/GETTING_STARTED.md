# Getting started with Loom

You need Java 17 or newer. No key, no account, no network.

```
$ weave try --fast
$ weave try --keep ./demo
$ weave check ./demo/main.loom --no-env
$ weave review ./demo/main.loom --no-env
$ weave test ./demo/main.loom
$ weave next ./demo
```

1. `weave try` runs a real workflow on demo models (replies written in advance) and explains each step.
2. `--keep` leaves that project for you to open and edit.
3. `check`, `review` and `test` are free and need no key (`--no-env` skips the key check); the tests use scripted models too.
4. `next` says what to do next, in order: review, tests, coverage, a deploy preview, and the first real, capped run.

Installing: `jbang weave@srijithunni7182/llm4j try`, or see [install/README.md](../install/README.md).
Something not working: `weave doctor` says what is wrong and the one thing to do.
