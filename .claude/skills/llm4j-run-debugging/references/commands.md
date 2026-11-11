# The commands, and what each one is for

All of them read the run's journal, the folder given to `weave run --journal`. Run `weave man <command>` for every option.

## Looking

- `weave timeline <run>`: each step with its attempt, state and cost, the checkpoints reached and every rewind. `--json` for tools.
- `weave why <run>`: each choice the run made, over which values, where each value came from and the way it did not go. Add a statement to
  ask about it (`weave why <run> Main/s2`, `--line N`, `--node n7`), `--all` for every step, `--since STEP` for what came after, and
  `--format md|json` to paste or pipe. It refuses when the script changed since the run: `--script old.loom`. `--store DIR` takes a run id.
- `weave graph <script> --run <run> --format mermaid`: the run drawn on the graph. Several `--run`s show taken edges, untaken ones and the
  statements no run reached.
- `weave questions`: what waits for a person. `weave run <script> --trace`: what the agents think and do, live (it runs, so it spends).

## Trying a change without running anything

- `weave whatif <run> --at <step|node|line> ...`: copies the run, cuts it back to that moment, changes one thing and runs it forward.
  - What to change: `--answer Agent=text` (or `Agent={json}`), `--set var=value`, `--human text` (what a person answers),
    `--fail Target=category[:times]` (for example `Replier=rate_limit:2`), `--tool`, `--prompt Agent=@v2`, `--model Agent=id`, `--script edited.loom`.
  - What you get: where the two runs part, the new result and the effects that differ (new effects are marked unsafe).
  - `--models recorded` (default) reuses an answer only where its question is unchanged; `scripted` uses only the answers given; `live`
    asks real models where nothing is recorded, under `--max-cost` or `--max-tokens` and `--yes`. Effects are only ever simulated.
- `weave step <script> -i name=value --break <line|node|Agent|"when cond">`: one statement at a time with mock models. At the prompt:
  `next`, `into`, `out`, `continue`, `back`, `set a = value`. `--journal DIR` keeps the steps for `weave why` and `weave resume`;
  `--record FILE` and `--replay FILE` keep and replay a session.
- In VS Code, F5 on a `.loom` file starts the same debugger (`weave dap`).

## Changing a run (ask first)

- `weave fork <run> --to <new-dir> [--at STEP] [--set k=v] [--script other.loom] [--effects keep|simulate|repeat] --reason "..."`: a copy to
  work on. The original is only read. `simulate` performs nothing in the copy.
- `weave rewind <run> --to <checkpoint|start|step> --reason "..." [--set k=v] [--resume]`: sends the run back; nothing is deleted, the old
  attempt stays as history. `--effects` defaults to `ask-first`, which refuses when an effect is already done.
- `weave reset <run> --reason "..." [--failed] [--resume]`: the run again from the top as a new attempt, or only the failed steps.
- `weave resume <run>`: carries on a paused run. `weave answer`: answers a waiting question.

## Keeping it fixed

- `weave capture <run> [--as test|golden|both] [--select failed|diverted|unsure|overridden] [--list]`: a real run as a test case, masked;
  `observed` until `--approve <file>`.
- `weave test <script> [--case NAME] [--coverage]`: runs the cases with scripted models; nothing is spent. `--check` validates the files only.
- `weave regress <candidate.loom> [--store DIR] [--prompt Agent=@v2] [--model Agent=id]`: tries a candidate script, prompt or model on a sample of past runs with recorded answers only (no model, no tool, no effect); unsafe changes first; `--fail-on` picks what makes it exit 1.
- `weave gate`: one verdict from the checks the team chose, for CI.

## What these do not cover

Real effects while debugging, the agent's own attempts and latency (the journal does not hold them), the branches of a `parallel` block as
separate threads, and what a real model would say to a question nobody has asked it before.
