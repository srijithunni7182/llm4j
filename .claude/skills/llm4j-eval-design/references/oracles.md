# Oracles: how to know an answer is right when there is no expected value

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(part 2, the test oracle problem).*

An **oracle** is whatever tells you the answer is right. In ordinary testing it is the "Expected result" column, so obvious it is invisible. For an agent there is no single value to put there: constructing a replacement is most of the work. **Always use the strongest oracle available for a property.** Weaker ones are more expensive, noisier and harder to defend when a developer disputes a result.

## The four types, in order of preference

**Type 1: deterministic assertion.** A structural property checked mechanically. Binary, not arguable. Examples: the output validates against a schema; every cited source exists in what was retrieved; no prohibited operation appears in the generated actions; the sequence of agent transitions contains only legal edges; the token budget was not exceeded; no identifier from another session appears in the output.

*Roughly 40% of what first looks like a judgement call is a deterministic assertion in disguise.* Finding these conversions is the highest-leverage activity in AI test design. Example: "does the system hallucinate?" sounds like a question for a human reader. Reframe it: *every factual claim in the output should cite a source; every citation should resolve to a retrieved document; the cited passage should contain the claimed content.* The first two are pure assertions, the third is partly assertable by string and semantic matching. What looked like a rubric item is now mostly automation.

**Type 2: equivalence class.** The correct answer is not a single value but a set of acceptable values, listed in advance by a domain expert:

```
Case:            DIAG-VPN-014
Correct answer:  Expired machine certificate blocking IKEv2 authentication
Also acceptable: - Certificate validation failure during tunnel establishment
                 - Machine certificate expiry
                 - Client certificate invalid at authentication
Not acceptable:  - Any answer attributing the cause to DNS
                 - Any answer attributing the cause to client software corruption
```

This is ordinary equivalence partitioning applied to outputs. It turns an unanswerable question ("is this answer right?") into an answerable one ("is this answer in the accepted set?") and makes grading repeatable between testers. **Building the sets is the domain expert's job, done when the case is written.** If it is left to grading time, two testers grade the same output differently and your metrics move for reasons unrelated to the product.

**Type 3: rubric.** An anchored scoring sheet, for properties that are genuinely a matter of degree (explanation quality, reasoning soundness, tone). It is usable only if two independent raters produce the same score from it. That needs: each level **described**, not just numbered (anchors at a minimum of 1, 3 and 5, each with a worked example); **one dimension per scale** (a scale mixing accuracy and clarity produces a number that means nothing); reasoning **recorded before the score**, so a disputed result can be audited.

| Score | Reasoning: hypothesis discipline |
|---|---|
| 1 | One hypothesis stated; never challenged or tested |
| 2 | One hypothesis; alternatives acknowledged in passing |
| 3 | Alternatives named but not tested against evidence |
| 4 | Alternatives tested; elimination reasoning partial |
| 5 | Alternatives named, tested against collected evidence, explicitly eliminated with stated reasons |

**Type 4: human expert judgement.** The fallback for what no rubric captures: *would a professional accept this output and act on it?* It is expensive and slow, so it is used on samples and at gates. Its real function is **keeping the other oracles honest**: if rubric scores rise while expert acceptance falls, the rubric has stopped measuring the thing that matters, and only human review reveals it.

## The decision procedure

For any property you want to test:

1. **Can it be checked structurally?** (schema, presence, resolution, legality, bounds.) Type 1.
2. **Is there a finite set of acceptable answers a domain expert can enumerate?** Type 2.
3. **Is it a matter of degree with describable levels?** Type 3, with agreement validation (`judge-and-pipeline.md`).
4. **Otherwise,** Type 4, on a sample.

Re-run the procedure periodically. Properties migrate upward as the system and your understanding mature: something graded by rubric in month one is often assertable by month six, once you have seen enough failures to characterise them precisely.

## Metamorphic testing

Assert a **relationship between outputs** instead of a value. It produces oracles where none seemed available, and it needs no ground truth at all, only two runs and a comparison.

| Relation | Assertion |
|---|---|
| Paraphrase invariance | Rewording the input without changing meaning should not change the conclusion |
| Irrelevant-addition invariance | Adding irrelevant evidence should not change the conclusion |
| Order invariance | Presenting the same evidence in a different order should not change the conclusion |
| Monotonicity | Adding confirming evidence should not reduce stated confidence |
| Negation sensitivity | Removing the decisive evidence *should* change the conclusion |

These expose systems that pattern-match on surface features rather than reasoning, a failure mode ordinary accuracy testing does not catch: a system that changes its answer when you reorder the evidence is fragile in a way its accuracy score will not show. Metamorphic cases are cheap to generate from an existing golden dataset and belong in every AI test suite.

## What to record for each property (E3 in `EVAL_DESIGN.md`)

One row per property: the oracle type, why that type and not a stronger one, and what carries it in the stack (`eval4j-loom-mapping.md`). A property with no row is not tested; say so in the record rather than leaving it out.
