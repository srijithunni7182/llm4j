# eval4j calibration study

_Synthetic datasets labelled by the module author (not independent humans) with planted defects; figures are optimistic relative to a human-labeled study._

Datasets: 12 RAG cases, 8 recall cases, 4 conversation metrics x 6 clean/defective pairs, 12 pairwise pairs.

## Judge: `claude-haiku-4-5-20251001`

### Contextual relevancy / precision

- Per-chunk agreement with labels: 100.0% (target >= 80%) - PASS
- Cohen's kappa (per chunk): 1.00 (target >= 0.50)
- Mean abs error, relevancy: 0.000; precision: 0.000

### Contextual recall

- Mean score - full support: 1.00, half: 0.50, none: 0.00
- Ordering full > half > none: yes; mean abs error vs expected: 0.000

### Conversation metrics (clean vs planted defect)

| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25 | >= 80% pairs |
|---|---|---|---|---|---|---|
| Knowledge retention | 0.75 | 0.17 | 0.58 | 6/6 | PASS | PASS |
| Role adherence | 1.00 | 0.67 | 0.33 | 6/6 | PASS | PASS |
| Completeness | 0.67 | 0.25 | 0.42 | 4/6 | PASS | FAIL |
| Relevancy | 1.00 | 0.61 | 0.39 | 6/6 | PASS | PASS |

### Pairwise comparison

- With swap mitigation: 12/12 correct, 12 decisive, 0 decisive-but-wrong (agreement on decisive: 100%, target >= 75%)
- Order-flip rate without mitigation: 0/12 pairs (0%) - the swap check turns these into ties instead of wrong wins

### Stability (relevancy, 3 cases, no cache)

- Mean score std-dev: samples(1) over 5 runs = 0.000; samples(3) over 3 runs = 0.000

_Wall time: 174 s_

## Judge: `claude-sonnet-5-5`

### Contextual relevancy / precision

- Per-chunk agreement with labels: 100.0% (target >= 80%) - PASS
- Cohen's kappa (per chunk): 1.00 (target >= 0.50)
- Mean abs error, relevancy: 0.000; precision: 0.000

### Contextual recall

- Mean score - full support: 1.00, half: 0.50, none: 0.00
- Ordering full > half > none: yes; mean abs error vs expected: 0.000

### Conversation metrics (clean vs planted defect)

| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25 | >= 80% pairs |
|---|---|---|---|---|---|---|
| Knowledge retention | 0.92 | 0.42 | 0.50 | 6/6 | PASS | PASS |
| Role adherence | 1.00 | 0.67 | 0.33 | 6/6 | PASS | PASS |
| Completeness | 0.92 | 0.50 | 0.42 | 5/6 | PASS | PASS |
| Relevancy | 1.00 | 0.67 | 0.33 | 6/6 | PASS | PASS |

### Pairwise comparison

- With swap mitigation: 12/12 correct, 12 decisive, 0 decisive-but-wrong (agreement on decisive: 100%, target >= 75%)
- Order-flip rate without mitigation: 0/12 pairs (0%) - the swap check turns these into ties instead of wrong wins

_Wall time: 141 s_

## Cross-judge consistency

- claude-haiku-4-5-20251001: recall full>half>none=true; Knowledge retention clean>defect=true; Role adherence clean>defect=true; Completeness clean>defect=true; Relevancy clean>defect=true; pairwise wrong-decisive=0
- claude-sonnet-5-5: recall full>half>none=true; Knowledge retention clean>defect=true; Role adherence clean>defect=true; Completeness clean>defect=true; Relevancy clean>defect=true; pairwise wrong-decisive=0
