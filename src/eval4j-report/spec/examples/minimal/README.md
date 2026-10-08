# Example bundles

Two small run bundles of the same golden dataset on branch `main`: a **previous** run and a **candidate** run. They are the shared fixtures for the schema tests, the reader, the analysis and the comparison.

| File | Purpose |
|---|---|
| `previous/` | run `…147`, commit `3ab91d0`, agent prompt `support-agent v2`, dataset rev 11. Eight evaluations, all passing. |
| `candidate/` | run `…148`, commit `9f2c4e1`, agent prompt `support-agent v3`, dataset rev 12. Ten lines, see below. Includes an agent trace, a workflow trace and an optimizer run. |
| `expected.json` | The golden results of the algorithms in [03-REPORT-CORE.md](../../03-REPORT-CORE.md) over these bundles: the candidate's rollups and coverage, and the comparison counts. Implementations must reproduce it exactly. |

What the candidate exercises:

| Feature | Where |
|---|---|
| a judged answer that flipped to failing | `refund-inside-window` / `answer-correctness` |
| a flip **inside the judge noise band** (Δ −0.03 ≤ 0.07) | `shipping-canada` / `faithfulness` |
| a `REUSED` verdict | `shipping-canada` / `answer-correctness` |
| a `CARRIED` result from an earlier full run | `gdpr-data-export` / `answer-correctness` (a key only in the candidate, so `NEW`) |
| a `NOT_EVALUATED` line | `gdpr-data-export` / `faithfulness` |
| a dimension **declared but not evaluated** | `compliance` (declared by `gdpr-data-export`, no metric) |
| a workflow trace that took the wrong branch | `research-and-publish-sufficient` (expected `S,R,A,W,P,E`; actual includes `L`) |
| an agent trace with an `UNKNOWN_TOOL` step | trace `t_refund` |
| a measured metric, an assertion, a pairwise metric registry entry | `latency-p95`, `tool-order`, `prompt-v3-vs-v2` |
| an optimizer run | `optimizations.jsonl` |

Headline expectations (also in `expected.json`): candidate counted **9** (5 passed, 4 failed, 1 not evaluated), pass rate **55.56%**; comparison **matched 8**, **worse 4**, better 0, same 4, **within noise 1**, **new 1**, removed 0, not comparable 0.
