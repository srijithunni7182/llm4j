# Prompt optimizer - multi-task efficacy study

_Three synthetic, author-made tasks; each starts from a weak seed prompt that hides a policy. GT = deterministic ground truth computed outside any judge; J1 = optimization judge's test mean; J2 = stronger independent judge (judged tasks only). Budget per run: 200 rollouts, 1000 LLM calls._

## qa

Seed prompt: `Answer the question using the passage.`; 40 scenarios.

| seed | GT seed | GT best | J1 seed | J1 best | J2 seed | J2 best | generalized | stop | rounds | rollouts | LLM calls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.00 | 0.38 | 0.13 | 0.97 | 1.00 | 1.00 | true | NO_PROGRESS | 6 | 80 | 161 |

<details><summary>qa seed 1 best prompt and verdict</summary>

```
Answer the question using only the passage. Reply with a short phrase of at most 8 words: just the answer itself, with no preamble such as "According to the passage", no full-sentence restatement, no explanation, and no markdown formatting. If the passage does not contain the answer, reply with a brief phrase such as "Not stated in the passage".
```

test mean 0.969 vs seed 0.125 (+0.844); confirmation validation 0.896; no guardrail violations

</details>

| 2 | 0.00 | 0.50 | 0.00 | 0.75 | 1.00 | 1.00 | false | NO_PROGRESS | 6 | 92 | 188 |

<details><summary>qa seed 2 best prompt and verdict</summary>

```
Answer the question using the passage. Reply with only the answer as a short phrase of no more than 8 words (ideally just the key word or words, e.g. a name, place, number, or year). Do not write a full sentence, do not begin with phrases like "According to the passage", and do not add explanation or formatting such as bold text.
```

2 test scenario(s) violate a guardrail

</details>

