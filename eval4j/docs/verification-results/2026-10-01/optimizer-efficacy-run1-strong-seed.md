# Prompt optimizer efficacy study

_Synthetic ticket-routing task, author-made; one task and few seeds, so treat as indicative._

Scenarios: 60 (split 50/30/20 per seed). Budget: 300 rollouts. Seed prompt: `You route customer support tickets. Categories: billing, technical, account, shipping, other. Reply with exactly one category word and nothing else.`

| run | seed GT | best GT | seed J1 | best J1 | seed J2 | best J2 | generalized | stop | rounds | rollouts | LLM calls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| seed 1 | 0.75 | 0.75 | 0.75 | 0.75 | 0.75 | 0.75 | false | TARGET_REACHED | 0 | 30 | 60 |

<details><summary>seed 1 best prompt and verdict</summary>

```
You route customer support tickets. Categories: billing, technical, account, shipping, other. Reply with exactly one category word and nothing else.
```

no candidate improved on the seed

</details>

| seed 2 | 0.92 | 0.92 | 0.92 | 0.92 | 0.92 | 0.92 | false | TARGET_REACHED | 0 | 30 | 60 |

<details><summary>seed 2 best prompt and verdict</summary>

```
You route customer support tickets. Categories: billing, technical, account, shipping, other. Reply with exactly one category word and nothing else.
```

no candidate improved on the seed

</details>

| seed 3 | 0.83 | 1.00 | 0.83 | 1.00 | 0.83 | 1.00 | true | TARGET_REACHED | 7 | 154 | 311 |

<details><summary>seed 3 best prompt and verdict</summary>

```
You route customer support tickets. Categories: billing, technical, account, shipping, other. Reply with exactly one category word and nothing else.

Choose the category by what the customer is asking the company to do (the action or problem to be resolved), not by the topic words that merely appear in the ticket.
- billing: charges, payments, invoices, refunds, credits, pricing, subscription fees, or any request involving money back or a payment issue, even if the cause is a shipping problem (e.g. a refund for a late delivery or shipping fee is billing).
- shipping: anything about delivery or fulfillment of physical orders, including delivery status, tracking, delays, lost or damaged parcels, address changes for an order, returns logistics, and pre-purchase questions about delivery (e.g. whether you deliver to a country or region, shipping options, delivery times, carriers, international shipping availability).
- technical: bugs, errors, crashes, site/app/device not working, setup or connectivity problems.
- account: login, password, profile, email/username changes, account access, security, or account deletion.
- other: only when the ticket does not fit any of the above, such as feedback, partnerships, press, or general company questions unrelated to billing, delivery, technical issues or accounts. Do not use other for a short or general-sounding question if it clearly concerns one of the other categories' subjects; pick that category instead.

If a ticket mixes topics, pick the category matching the main requested outcome. Money-related requests (refund, charge, payment) take priority as billing.
```

test mean 1.000 vs seed 0.833 (+0.167); confirmation validation 1.000; no guardrail violations

</details>

## Control: no optimization (noise)

Seed ground-truth accuracy on test, two independent runs: 0.750 and 0.750.

## Control: random edits

Random-edit rewriter: seed GT 0.75 -> best GT 0.75, generalized=false, stop=TARGET_REACHED.

