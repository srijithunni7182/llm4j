# eval4j calibration study - round 2 (harder dataset)

_Synthetic datasets labelled by the module author (not independent humans) with planted defects; figures are optimistic relative to a human-labeled study._

Dataset `round-2 (harder)`: 12 RAG cases, 8 recall cases, conversation pairs (retention 6, role 6, completeness 6, relevancy 6), 16 pairwise pairs.

## Judge: `claude-haiku-4-5-20251001`

### Contextual relevancy / precision

- Per-chunk agreement with labels: 98.1% (target >= 80%) - PASS
- Cohen's kappa (per chunk): 0.95 (target >= 0.50)
- Mean abs error, relevancy: 0.028; precision: 0.014

### Contextual recall

| Expected score | Cases | Mean judged score |
|---|---|---|
| 0.00 | 1 | 0.00 |
| 0.25 | 1 | 0.25 |
| 0.50 | 2 | 0.75 |
| 0.75 | 1 | 0.75 |
| 1.00 | 3 | 1.00 |

- Mean scores non-decreasing with expected support: yes; mean abs error vs expected: 0.063

### Conversation metrics (clean vs planted defect)

| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25 | >= 80% pairs |
|---|---|---|---|---|---|---|
| Knowledge retention | 0.92 | 0.50 | 0.42 | 5/6 | PASS | PASS |
| Role adherence | 1.00 | 0.67 | 0.33 | 6/6 | PASS | PASS |
| Completeness | 0.75 | 0.08 | 0.67 | 6/6 | PASS | PASS |
| Relevancy | 1.00 | 0.58 | 0.42 | 6/6 | PASS | PASS |

### Pairwise comparison

- Pairs with a better answer (12): 12 correct, 12 decisive, 0 decisive-but-wrong (agreement on decisive: 100%, target >= 75%)
- Equivalent-answer pairs (4): 3 judged TIE (a decisive win between equivalent answers is a false preference)
- Order-flip rate without mitigation: 3/16 pairs (19%)

_Wall time: 161 s_

## Judge: `claude-sonnet-5-5`

### Contextual relevancy / precision

- Per-chunk agreement with labels: 98.1% (target >= 80%) - PASS
- Cohen's kappa (per chunk): 0.95 (target >= 0.50)
- Mean abs error, relevancy: 0.028; precision: 0.014

### Contextual recall

| Expected score | Cases | Mean judged score |
|---|---|---|
| 0.00 | 1 | 0.00 |
| 0.25 | 1 | 0.25 |
| 0.50 | 2 | 0.58 |
| 0.75 | 1 | 0.75 |
| 1.00 | 3 | 1.00 |

- Mean scores non-decreasing with expected support: yes; mean abs error vs expected: 0.021

### Conversation metrics (clean vs planted defect)

| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25 | >= 80% pairs |
|---|---|---|---|---|---|---|
| Knowledge retention | 1.00 | 0.50 | 0.50 | 6/6 | PASS | PASS |
| Role adherence | 1.00 | 0.67 | 0.33 | 6/6 | PASS | PASS |
| Completeness | 0.92 | 0.25 | 0.67 | 6/6 | PASS | PASS |
| Relevancy | 1.00 | 0.64 | 0.36 | 6/6 | PASS | PASS |

### Pairwise comparison

- Pairs with a better answer (12): 12 correct, 12 decisive, 0 decisive-but-wrong (agreement on decisive: 100%, target >= 75%)
- Equivalent-answer pairs (4): 4 judged TIE (a decisive win between equivalent answers is a false preference)
- Order-flip rate without mitigation: 0/16 pairs (0%)

_Wall time: 161 s_

## Cross-judge consistency

- claude-haiku-4-5-20251001: recall monotone=true; Knowledge retention clean>defect=true; Role adherence clean>defect=true; Completeness clean>defect=true; Relevancy clean>defect=true; pairwise wrong-decisive=0
- claude-sonnet-5-5: recall monotone=true; Knowledge retention clean>defect=true; Role adherence clean>defect=true; Completeness clean>defect=true; Relevancy clean>defect=true; pairwise wrong-decisive=0
