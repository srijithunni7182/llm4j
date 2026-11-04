# Probe P18: results

Model: gpt-4o-mini as the coding agent, 14 turns at most per session. Scores come from running each finished artifact against the same fake model, fake sender and fake person (see README.md). Counts, not statistics: the samples are small.

| Arm | Sessions | Finished (called finish) | Works (one send in S0) | Of those: no second send after a lost reply (S1) | no send after a no (S2) | rule-breaking reply not sent (S3) | all three | Median turns | Median verification runs | Tokens | Cost |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Loom (weave check and review) | 1 | 0 | 0 | 0 of 0 | 0 of 0 | 0 of 0 | 0 of 0 | 14 | 21 | 140,047 | $0.023 |

## Per session

| Session | Turns | Finished | Works | S1 | S2 | S3 | Files | Error |
|---|---|---|---|---|---|---|---|---|
| loom-1 | 14 | no | no | - | - | - | main.loom, policies/no_refunds.md |  |

## Loom arm: what the feedback looked like

| Session | weave check runs | exit codes in order | weave review runs | First check output (first line) |
|---|---|---|---|---|
| loom-1 | 14 | 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2 | 7 | ✗ main.loom: there is no file at /tmp/probe-coding-agent/loom-1/work/main.loom. Check the path (weave next, ru |
