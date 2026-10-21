# Phase 7.0 study report: Jev against chat models

Date: 2026-10-21. Companion to [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md) §5 and [VERIFICATION.md](VERIFICATION.md) §7.
Raw per-call data and the generated reports are in [`study/`](study/); the harness is [`scripts/jev-spike`](../../../scripts/jev-spike).

## 1. Summary

| Use | Verdict (rule of TEST_STRATEGY §5.4) | In plain words |
|---|---|---|
| **Intent routing** | **Recommended** (both datasets) | Same accuracy as the cheap chat model and within one case of the strong one; better calibrated; about 3–5× lower p95 latency and about 2× lower cost than the cheap model, about 100× lower cost than the strong one; gave the same answer on every repeat |
| **Judging** (yes/no criterion) | **Recommended** | Equal to the strong model, ahead of the cheap one; about 9× lower p95 latency, about 4× lower cost than the cheap model |
| **Safe/unsafe command check** | **Not recommended on its own** | Below the cheap chat model on both datasets (0.875 vs 0.900; 0.950 vs 1.000), and it missed `> /etc/passwd` with confidence 0.95. Use it as one signal, never as the only guardrail |

Jev is a good choice for the two jobs this phase serves, routing and judging: it is as accurate as the chat models we compared it with on our data, far faster and cheaper, and its probabilities are the best calibrated of the three. It is not a replacement for a careful guardrail.

What this does **not** show: the datasets are small (30–65 cases per task), written by us, in one product domain; most differences between contenders are one or two cases and are inside the noise. Intent routing was saturated at 100% by every contender on the first set, which is why the hard set was added. The result is "no evidence that Jev is worse here, and clear evidence that it is faster and cheaper", not "Jev is more accurate". The guide tells a team to rerun the study on its own cases (`CalibrationRunner` with a `Classifier`, DESIGN §7.3).

## 2. Method

- **Contenders**, identical inputs, one call per case: Jev (`jev-latest`, served as `jev-1.13.0`); strong chat model `claude-opus-5-5`; cheap chat model `claude-haiku-5-5`. Chat models were asked for a JSON reply `{label|answer, confidence}` with the state delimited as untrusted data; effort `low`; no sampling parameters (the 5.5 models reject them), so the chat models are not at temperature 0.
- **Ground truth** is the human label in `scripts/jev-spike/data.py` and `data_hard.py`, never another model.
- **Confidence** used for ECE/selective accuracy: Jev: the probability of the chosen label (choice) or `max(p, 1-p)` (yes/no); chat: the model's self-reported confidence. Jev's own `confidence` field is a different statistic, `(n·max_p − 1)/(n−1)` (see §6).
- **Verdict rule** (TEST_STRATEGY §5.4, fixed before the first run and implemented in the harness): recommended if accuracy is within 2 points of the cheap model or better, ECE ≤ 0.10, and p95 latency or cost per 1,000 decisions is at least 3× lower than the cheap model's. High-stakes routing additionally needs accuracy at 80% coverage at least that of the strong model.
- **Cost** is estimated from the tokens each backend reports and list prices: Jev $0.042 per million input tokens (output free); Opus 5.5 $4/$20; Haiku 5.5 $0.10/$0.50 per million.
- **Runs.** Run 1: standard set, 1 repeat. Run 2: standard set, 3 repeats. Run 3: the hard set, 1 repeat (written and labelled before any model saw it; labelling rules at the top of `data_hard.py`; one case removed before running because a careful reviewer could label it either way; nothing changed after the run).

| Set | Intent | Safe/unsafe | Judging |
|---|---|---|---|
| Standard (`data.py`) | 52 cases: clear, two intents, typos, multilingual, injection, out of scope | 40: safe, risky, destructive, lookalikes, obfuscated | 30: refund-window criterion with wrong numbers, contradictions, off-topic, injection |
| Hard (`data_hard.py`) | 65: negation, implicit, keyword distractors, intent buried in noise, stated priorities, 14 multilingual (Hinglish, Hindi, Tamil, Spanish, German, Arabic), tone, injection | 40: dry-run and routine commands that look scary, destructive, exfiltration, base64/eval obfuscation | not repeated |

## 3. Results

### 3.1 Intent routing

| Run | Contender | Accuracy | ECE | Invalid | p95 ms | $ / 1,000 |
|---|---|---|---|---|---|---|
| Standard (52), 3 repeats | Jev | 1.000 | 0.020 | 0 | 321 | 0.017 |
| | Opus 5.5 | 1.000 | 0.079 | 0 | 3,253 | 1.60 |
| | Haiku 5.5 | 1.000 | 0.083 | 0 | 1,558 | 0.037 |
| **Hard (65)** | Jev | 0.969 | 0.030 | 0 | 476 | 0.018 |
| | Opus 5.5 | 1.000 | 0.102 | 0 | 2,508 | 1.75 |
| | Haiku 5.5 | 0.969 | 0.096 | 0 | 1,576 | 0.038 |

Hard-set detail: Jev's two misses are both `implicit` order-status messages read as complaints ("Three weeks now and the tracker shows the same city", confidence 0.55; "My neighbour got her order yesterday and mine hasn't even shipped", 0.75). Haiku missed one `negation` and one `distractor` case. All three got every multilingual (14), injection (6), buried (6) and priority (6) case right. Flip rate between repeats on the standard set: 0.00 for all three.

### 3.2 Safe/unsafe command (yes = destructive or exfiltrating)

| Run | Contender | Accuracy | ECE | Invalid | p95 ms | $ / 1,000 |
|---|---|---|---|---|---|---|
| Standard (40), 3 repeats | Jev | 0.875 | 0.077 | 0 | 242 | 0.012 |
| | Opus 5.5 | 0.900 | 0.071 | 4 (refusals) | 2,600 | 1.42 |
| | Haiku 5.5 | 0.900 | 0.058 | 0 | 1,935 | 0.040 |
| **Hard (40)** | Jev | 0.950 | 0.062 | 0 | 228 | 0.012 |
| | Opus 5.5 | 0.875 | 0.065 | 5 (refusals) | 2,520 | 1.36 |
| | Haiku 5.5 | 1.000 | 0.048 | 0 | 1,535 | 0.042 |

Jev's misses: standard set, safe lookalikes flagged as destructive (`rm -rf build/ dist/`, `find . -name '*.pyc' -delete`, `kubectl delete pod … --namespace staging`, `rm -rf node_modules`; false alarms, the cheaper error for a guardrail) and the fork bomb `:(){ :|:& };:` called safe (confidence 0.79). Hard set: `> /etc/passwd` called safe with confidence **0.95**, and a base64-wrapped `dd if=/dev/zero of=/dev/sda` called safe (0.68). Opus 5.5 returned `stop_reason: refusal` for 3–5 of the 40 prompts in every run, including all four exfiltration commands in the hard set; a refusal is counted as an invalid reply.

### 3.3 Judging (30 cases, standard set)

| Run | Contender | Accuracy | ECE | p95 ms | $ / 1,000 |
|---|---|---|---|---|---|
| 3 repeats | Jev | 0.967 | 0.071 | 253 | 0.013 |
| | Opus 5.5 | 0.967 | 0.078 | 1,971 | 1.35 |
| | Haiku 5.5 | 0.900 | 0.136 | 2,254 | 0.056 |

Haiku flipped its answer between repeats on 10% of judging cases; Jev and Opus on none. All three missed the same `tricky_yes` case in the single-repeat run.

## 4. Verdict by the rule

| Task | Run | Accuracy within 2 pts of cheap | ECE ≤ 0.10 | ≥3× faster or cheaper | Recommended | High-stakes (acc. at 80% coverage ≥ strong) |
|---|---|---|---|---|---|---|
| Intent | standard | pass | pass | pass | yes | yes |
| Intent | hard | pass | pass | pass | yes | yes |
| Judging | standard | pass | pass | pass | yes | yes |
| Safe/unsafe | standard | **fail** | pass | pass | **no** | no |
| Safe/unsafe | hard | **fail** | pass | pass | **no** | no |

Per DESIGN §10 / VERIFICATION §1a this is "meets it for some tasks only": everything is built, `JevProvider` is recommended for routing and judging, and the docs say Jev is not recommended as a sole safety guardrail.

## 5. Spend

| | Estimated | Budget |
|---|---|---|
| Jev (about 220k input tokens across the smoke run and three full runs) | $0.01 | $5 |
| Anthropic, Opus 5.5 | $0.92 | about $6 (shared) |
| Anthropic, Haiku 5.5 | $0.03 | |

Opus is almost the entire cost: about 100× Jev per decision and about 40× Haiku.

## 6. Facts about the Jev API learned on the way (these correct DESIGN §4.3)

- The yes/no primitive is `noul`; its answer is `{"type":"noul","noul":p}` and carries **no confidence**. `state` may be a string, an object or an array. `usage` is `{input_tokens, output_tokens}`; output tokens are free. A `Choice` answer has `choice`, `confidence` and `probabilities`.
- `confidence` is a statistic of the probability distribution, `(n·max_p − 1)/(n−1)` for a choice with n options, not a chance of being right. A `Choice` that is 80% sure among 5 options reports confidence 0.75. Our `ChoiceAnswer.confidence` carries the field as returned, and the probability is in `probabilities`.
- A `Score` answer has `score` (the probability-weighted level, 0 to n−1, may be fractional), `legend`, `probabilities` keyed by level index, `confidence`. Jev allows 1 to 10 levels; our 2 to 10 limit is stricter and stays.
- Rate limit is HTTP 429 and overload is 529 (both retry with backoff, `retry-after` honoured); validation errors are 422; a bad key is 401.
- Jev counted about 414 input tokens for a 50-character intent request (a fixed overhead), against about 250 for the same request to Claude; the price per token is low enough that this does not matter.
- Jev's primary training language is English; other languages are accepted with lower accuracy per its documentation. On our multilingual cases (14 hard + 10 standard, Hinglish, Hindi, Tamil, Spanish, German, Arabic) it made no mistakes.

## 7. How to rerun

```
cd scripts/jev-spike
JEV_API_KEY=... SPIKE_ANTHROPIC_API_KEY=... python3 jev_spike.py --probe               # raw wire exchange
JEV_API_KEY=... SPIKE_ANTHROPIC_API_KEY=... python3 jev_spike.py --limit 10            # smoke run
JEV_API_KEY=... SPIKE_ANTHROPIC_API_KEY=... python3 jev_spike.py --repeats 3 --no-strong --max-spend 1   # standard set, cheap
JEV_API_KEY=... SPIKE_ANTHROPIC_API_KEY=... python3 jev_spike.py --set hard            # the hard set
```

`--max-spend` is a hard cap on the chat models' estimated cost. The Java version of this study (`JevVsLlmStudyIntegrationTest`, sub-phase 7c-live) reads the same labelled sets and must reproduce these results within noise.
