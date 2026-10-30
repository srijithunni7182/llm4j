# Failure modes: the anti-patterns and the troubleshooting table

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(the anti-patterns of part 3, the troubleshooting table of the appendix), with what the llm4j field session added.*

## The anti-patterns (the checklist of review mode)

For each, the evidence that settles it, and the stage of `EVAL_DESIGN.md` it belongs to. If the files do not settle it, write **not verified**.

| # | Anti-pattern | Why it fails | Evidence to look for | Stage |
|---|---|---|---|---|
| 1 | The dataset was generated with the same model family as the system | it inherits the system's blind spots and passes its own weaknesses | `source:synthetic` tags and who generated them; `author:` tags; the git history of the dataset | E4 |
| 2 | Only cases the system currently passes | the suite becomes a certificate instead of a measurement | the pass rate on the first real run (a first run at 100% is a warning); the proportion of `difficulty:hard` and `difficulty:adversarial` | E4 |
| 3 | No unanswerable cases (nor the other two missing classes) | confident fabrication goes undetected | a count of `class:` tags or of cases whose expected outcome is a refusal or an escalation | E4 |
| 4 | Ground truth written by the test team without domain review | it measures agreement with the team's assumptions | `reviewed_by:` tags; `weave eval --check` reports drafted cases no person reviewed | E4, E10 |
| 5 | Cases with no metadata | you cannot slice, so you cannot diagnose | tags per case; the report's *By slice* | E4 |
| 6 | A dataset owned by whoever owns the prompts | the measured party controls the instrument | the ownership section; who commits to `eval/golden` | E10 |
| 7 | Never refreshed | it drifts out of alignment with the real input distribution while reporting confident numbers | the dates of the last changes to the dataset; the share derived from real failures | E4 |
| 8 | An unvalidated judge that gates | a second unvalidated model decides correctness | the report's judge line says whether it was validated; the gate table; any record of a human-scored reference set | E9 |
| 9 | Thresholds set at current performance | they fail half the time on noise, or never | the gate table: is there an absolute floor and a regression band, or a single number equal to today's? | E9 |
| 10 | A mock result quoted as quality | a mock proves the wiring, nothing about content | a report line "mock", content checks *unjudged*; any sentence in the README or PR that quotes a pass rate from a mock | E0, E9 |
| 11 | One run reported as a result | variation is by design | `--repetitions` absent; *Each case ran once* in the report | E6 |
| 12 | A held-out set the team can read and tune against, or none | prompts get tuned until the test set passes | `split:` tags; a report without a generalisation gap | E4 |
| 13 | Re-baselining to hide a regression | each step looks reasonable while quality slides | `eval/baselines/` entries without an approver and a reason | E8 |
| 14 | A percentage where a control is required ("99% injection resistance") | one crafted payload in a hundred succeeds | the security section: are the controls verified at 100%, with the model fooled? | E7 |
| 15 | No fault tests, so a tool error can be ignored silently | the characteristic agent failure | `fail:` cases in `weave test`; the trajectory tests | E5, E7 |
| 16 | No drift check | provider changes arrive with no commit | the run protocol: a weekly fixed-configuration run | E6 |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Scores swing between identical runs | working as designed: non-determinism | increase repetitions; check the variance band before investigating |
| One case flips 2/5, 3/5, 2/5 | the case is under-specified | send it to the dataset owner; usually an incomplete accepted-answers list |
| Everything got worse overnight, no commits | a provider-side model change | check the pinned version and the provider's release notes; run the weekly drift comparison |
| Judge scores rising, human agreement falling | rubric drift | stop gating on that rubric; re-validate the judge; rewrite the anchors |
| High unmatched rate (above about 10%) | ground truth incomplete | route unmatched answers to human review; add accepted equivalents |
| CI fails intermittently on thresholds | a hard threshold set at current performance | an absolute floor plus a regression band with a tolerance |
| Evaluation cost exploding | judging every run, no fail-fast, no caching | sample the judge nightly; fail on the cheap layer first; cache by content hash |
| Fixtures missing mid-run | a new code path calls a resource never recorded | re-record the fixtures; consider whether the new call is itself correct |
| Rate-limit errors from the provider | the harness parallelism is too high | reduce workers, add back-off; do not record these as system failures |
| pass@1 good, pass^N poor | genuine inconsistency | a real finding: report it, users will feel it |
| Held-out much worse than validation | overfitting to the dataset | refresh the dataset; check whether prompts were tuned against validation cases |
| The free tests say "ready" and a real run finds a prompt injection that worked | the free tests prove wiring, not behaviour | a real, capped evaluation that includes the corpus, and the controls check |
| An answer is whole in tests and cut in production | the output limit differs (the report says how many answers were cut at the limit) | read the real answer under a cap; check the per-agent limits (`weave explain`) |

## A defect report for a statistical failure

A failure that appears in some runs is reported with its rate, the case, the exact configuration, and a passing run next to a failing one, so the developer can see the difference:

```
Title:   [n/N] <dimension> - <specific failure> - <CASE-ID>
Rate:    n of N runs
Case:    <id and version>
Config:  model / prompt / workflow / tools / temperature / dataset version (the fingerprint)
Check:   <which assertion or rubric line failed>
Traces:  <failing run ids>, <passing run ids>
Impact:  <what a user experiences>
```
