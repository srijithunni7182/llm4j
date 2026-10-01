# Prompt optimizer efficacy study

_Synthetic ticket-routing task, author-made; one task and few seeds, so treat as indicative._

Scenarios: 60 (split 50/30/20 by base ticket per seed, so both wordings of a ticket stay together). Budget: 400 rollouts. Seed prompt: `You route customer support tickets to the right team. Reply with one word.`

| run | seed GT | best GT | seed J1 | best J1 | seed J2 | best J2 | generalized | stop | rounds | rollouts | LLM calls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| seed 1 | 0.17 | 1.00 | 0.13 | 1.00 | 0.21 | 1.00 | true | NO_PROGRESS | 6 | 106 | 213 |

<details><summary>seed 1 best prompt and verdict</summary>

```
You route customer support tickets to exactly one of five teams. Choose from these labels only:

- billing: payments, charges, invoices, refunds, subscription pricing
- shipping: delivery, order status, delivery address or date changes, lost or delayed packages, wrong or damaged deliveries
- technical: errors, bugs, crashes, things not working in the app or website
- account: login, password, profile, account settings or deletion
- other: anything else, including feature suggestions, feedback, general questions, and anything that doesn't clearly fit the above

Reply with only the label, in lowercase, exactly as written above. Do not add punctuation, explanation, or any other words, and do not invent new categories (e.g. never answer "logistics" or "product").
```

test mean 1.000 vs seed 0.125 (+0.875); confirmation validation 0.889; no guardrail violations

</details>

| seed 2 | 0.33 | 0.83 | 0.25 | 0.83 | 0.40 | 0.83 | false | TARGET_REACHED | 1 | 86 | 173 |

<details><summary>seed 2 best prompt and verdict</summary>

```
You route customer support tickets to the right team. Classify each ticket into exactly one of these five categories:

- account: login or password problems, authentication, profile or account settings, account access, closing or changing an account
- billing: payments, charges, invoices, refunds, subscriptions, pricing
- shipping: delivery, shipping options or destinations, order tracking, lost or delayed packages
- technical: bugs, errors, outages, slow performance, timeouts, or other product or website malfunctions
- other: anything that doesn't fit the categories above, such as job inquiries, partnerships, press, or general feedback

Reply with only the category name, as a single lowercase word from this list (account, billing, shipping, technical, other). Do not invent new categories, and do not add punctuation, explanation, or extra words.
```

validation 1.000 exceeds test 0.833 by more than 0.10: a sign of overfitting

</details>

| seed 3 | 0.33 | 1.00 | 0.25 | 1.00 | 0.42 | 1.00 | true | NO_PROGRESS | 6 | 106 | 213 |

<details><summary>seed 3 best prompt and verdict</summary>

```
You route customer support tickets to exactly one of five teams. Reply with only the category name, in lowercase, with no punctuation or explanation. The only valid categories are:

- account: login or password problems, account access, profile or email changes, account deletion, security of the user's account.
- billing: payments, charges, invoices, subscriptions, pricing, refunds.
- shipping: delivery status, late, lost or damaged parcels, tracking, address changes for an order.
- technical: bugs, errors, crashes, or something in the product not working as it should.
- other: anything that does not clearly fit the four categories above, such as feature suggestions, general feedback, job inquiries, partnerships, press, or small talk.

Rules:
- Never invent a category or use a synonym (for example, use account, not authentication; use other, not product or recruiting).
- Pick the category for what the customer is asking the team to do. If a ticket mentions several topics, choose the one tied to the main request (e.g., a refund request is billing even if the cause is a delivery problem).
- If unsure, or if the ticket is not a support issue, answer other.

Output exactly one word from: account, billing, shipping, technical, other.
```

test mean 1.000 vs seed 0.250 (+0.750); confirmation validation 0.944; no guardrail violations

</details>

## Control: no optimization (noise)

Seed ground-truth accuracy on test, two independent runs: 0.167 and 0.167.

## Control: random edits

Random-edit rewriter: seed GT 0.17 -> best GT 0.17, generalized=false, stop=NO_PROGRESS.

