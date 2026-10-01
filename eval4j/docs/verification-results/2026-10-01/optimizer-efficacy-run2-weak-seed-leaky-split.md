# Prompt optimizer efficacy study

_Synthetic ticket-routing task, author-made; one task and few seeds, so treat as indicative._

Scenarios: 60 (split 50/30/20 per seed). Budget: 400 rollouts. Seed prompt: `You route customer support tickets to the right team. Reply with one word.` (the weak seed; the header originally printed the strong seed by mistake)

| run | seed GT | best GT | seed J1 | best J1 | seed J2 | best J2 | generalized | stop | rounds | rollouts | LLM calls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| seed 1 | 0.42 | 1.00 | 0.33 | 1.00 | 0.46 | 1.00 | true | TARGET_REACHED | 1 | 86 | 173 |

<details><summary>seed 1 best prompt and verdict</summary>

```
You route customer support tickets to the right team. Classify each ticket into exactly one of these five categories:

- billing: invoices, charges, payments, refunds, pricing, subscriptions
- shipping: delivery, couriers, parcels, lost, delayed or stolen packages, shipping destinations and tracking
- account: login, password, profile and email changes, account settings, account deletion
- technical: bugs, errors, crashes, product malfunctions, how to use features
- other: anything that fits none of the above

Rules:
- Reply with only the category name, as a single lowercase word, exactly as written above (billing, shipping, account, technical, other).
- Do not invent other labels (for example, never answer "logistics"); map every ticket to the closest of the five.
- No punctuation, explanation, capitalization or extra text.
```

test mean 1.000 vs seed 0.333 (+0.667); confirmation validation 1.000; no guardrail violations

</details>

| seed 2 | 0.50 | 1.00 | 0.38 | 1.00 | 0.58 | 1.00 | true | TARGET_REACHED | 1 | 86 | 173 |

<details><summary>seed 2 best prompt and verdict</summary>

```
You route customer support tickets to exactly one of these five categories:

- billing: payments, charges, invoices, pricing, refunds, and money-back requests (including refunds for broken or defective items)
- shipping: delivery availability, shipping options, delivery times, tracking, and lost or delayed packages
- technical: bugs, errors, crashes, login or setup problems, and other malfunctions of the product or app
- account: profile, password, email changes, subscription management, and account deletion
- other: anything that does not clearly fit the above, including feature suggestions, general feedback, company information such as opening hours, and small talk

Rules:
- Choose only from these five labels. Never invent new labels such as "product", "returns" or "reception"; if nothing fits, use other.
- Reply with exactly one label: lowercase, a single word, no punctuation, no explanation.
```

test mean 1.000 vs seed 0.375 (+0.625); confirmation validation 1.000; no guardrail violations

</details>

| seed 3 | 0.50 | 1.00 | 0.38 | 1.00 | 0.56 | 1.00 | true | TARGET_REACHED | 1 | 86 | 173 |

<details><summary>seed 3 best prompt and verdict</summary>

```
You route customer support tickets to the right team. Choose exactly one category from this fixed list and reply with only that category word, in lowercase, with no punctuation or explanation:

account
billing
technical
shipping
other

Rules:
- Use only the words in the list above. Never invent new labels (for example do not answer "praise", "authentication", "login" or "feedback").
- account: anything about a user's profile or access to it, including login problems, wrong or forgotten passwords, authentication, email or username changes, and account deletion.
- billing: charges, invoices, refunds, payments, subscriptions and pricing.
- technical: bugs, errors, crashes, or the product not working as expected, when the user can already access their account.
- shipping: delivery, tracking, and order status or delays.
- other: anything that does not clearly fit the categories above, including compliments, thanks, praise, general comments and small talk.
- If a ticket could fit several categories, pick the one that describes the main problem the customer needs solved.

Reply with the single category word only.
```

test mean 1.000 vs seed 0.375 (+0.625); confirmation validation 1.000; no guardrail violations

</details>

## Control: no optimization (noise)

Seed ground-truth accuracy on test, two independent runs: 0.417 and 0.417.

## Control: random edits

Random-edit rewriter: seed GT 0.42 -> best GT 0.42, generalized=false, stop=NO_PROGRESS.


> **Caveat (found after this run):** each base ticket appears twice with different wording and the split was
> random, so one wording could be in train and the other in test. The rewriter can therefore have seen
> near-duplicates of test tickets, which may inflate these gains. Run 3 repeats the study with both
> wordings kept in the same split.
