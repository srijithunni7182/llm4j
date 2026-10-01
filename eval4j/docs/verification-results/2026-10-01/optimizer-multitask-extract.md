# Prompt optimizer - multi-task efficacy study

_Three synthetic, author-made tasks; each starts from a weak seed prompt that hides a policy. GT = deterministic ground truth computed outside any judge; J1 = optimization judge's test mean; J2 = stronger independent judge (judged tasks only). Budget per run: 250 rollouts, 1500 LLM calls._

## extract

Seed prompt: `Extract the vendor, date and total from the text as JSON.`; 40 scenarios.

| seed | GT seed | GT best | J1 seed | J1 best | J2 seed | J2 best | generalized | stop | rounds | rollouts | LLM calls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.00 | 1.00 | 0.29 | 1.00 | n/a | n/a | true | TARGET_REACHED | 1 | 60 | 61 |

<details><summary>extract seed 1 best prompt and verdict</summary>

```
Extract the vendor, date and total from the invoice text and return a single JSON object with exactly these keys: "vendor", "date", "total_cents".

Rules:
- "vendor": the vendor's name without any legal-entity suffix (remove Inc., Inc, LLC, Ltd, Ltd., Corp., Co., GmbH, etc., and any trailing punctuation). Keep the rest of the name exactly as written, including characters like "&". Example: "Acme Tools Inc." -> "Acme Tools".
- "date": the invoice date in ISO 8601 format, YYYY-MM-DD, regardless of how it is written in the text (e.g. "July 25, 2024" -> "2024-07-25").
- "total_cents": the total amount due as an integer number of the smallest currency unit (cents), with no currency symbol, no separators, and no decimals. Handle both "1,040.13" and "198,47" style number formats (comma or period as decimal separator, and thousands separators). Example: $445.12 -> 44512; 1,040.13 -> 104013. Use the total/amount due/payable, not subtotals. Do not output the currency symbol or code.

Output only the JSON object, with no extra keys and no commentary. If a field cannot be found, use null for it.
```

test mean 1.000 vs seed 0.292 (+0.708); confirmation validation 1.000; no guardrail violations

</details>

| 2 | 0.00 | 1.00 | 0.25 | 1.00 | n/a | n/a | true | TARGET_REACHED | 1 | 60 | 61 |

<details><summary>extract seed 2 best prompt and verdict</summary>

```
Extract the vendor, date and total from the invoice text and respond with ONLY a single JSON object (no code fences, no commentary) with exactly these keys:

- "vendor": string. The company's core name without legal-entity suffixes such as LLC, Ltd, GmbH, Inc, Corp, AG, S.A., PLC, Co. (e.g. "Acme Holdings Inc" -> "Acme Holdings").
- "date": string. The invoice date in ISO 8601 format YYYY-MM-DD (e.g. "July 25, 2023" -> "2023-07-25").
- "total_cents": integer. The total amount due in minor currency units (cents), with no currency symbol, code, decimal point or separators. Handle both US formats (1,040.13) and European formats (1.617,95 or 270,21): the last separator followed by exactly two digits is the decimal mark; other separators are thousands separators. Examples: 1,040.13 -> 104013; 270,21 -> 27021; 1.617,95 -> 161795.

Do not include a "total" key or a currency field. If a value is missing from the text, use null for it.
```

test mean 1.000 vs seed 0.250 (+0.750); confirmation validation 1.000; no guardrail violations

</details>

| 3 | 0.00 | 1.00 | 0.25 | 1.00 | n/a | n/a | true | TARGET_REACHED | 1 | 60 | 61 |

<details><summary>extract seed 3 best prompt and verdict</summary>

```
Extract the vendor, invoice date and total from the invoice text and return ONLY a single JSON object (no prose, no markdown code fences) with exactly these keys:

- "vendor": the vendor's name without any legal-entity suffix such as LLC, Inc., Inc, GmbH, Ltd, Ltd., Corp., Co., AG, S.A., PLC. Keep the rest of the name as written (e.g. keep "&" and multi-word names).
- "date": the invoice date in ISO 8601 format YYYY-MM-DD, regardless of how it is written in the text (e.g. "March 5, 2021" or "Mar 5 2021" -> "2021-03-05").
- "total_cents": the total amount as an integer number of the minor currency unit (cents), with no currency symbol, separators or decimals. Interpret both European (1.246,81) and US (1,246.81) number formats correctly: the last separator followed by exactly two digits is the decimal mark. Examples: 198,47 -> 19847; $144.99 -> 14499; 1.246,81 -> 124681.

Do not include a currency field or any other keys. If a field cannot be found, use null for it. Output numbers as JSON integers, not strings.
```

test mean 1.000 vs seed 0.250 (+0.750); confirmation validation 1.000; no guardrail violations

</details>

