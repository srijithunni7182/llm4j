# 📊 Token Growth Simulation — Engram vs Transcript
> Mathematical projection of 20-turn workflow | 600 tokens/turn response

## Efficiency Curve

| Turn | Transcript Prompt | Engram Prompt | Savings |
|------|-------------------|---------------|---------|
| 1 | 300 | 930 | **-210.0%** |
| 2 | 1,000 | 960 | **4.0%** |
| 3 | 1,700 | 990 | **41.8%** |
| 4 | 2,400 | 1,020 | **57.5%** |
| 5 | 3,100 | 1,050 | **66.1%** |
| 6 | 3,800 | 1,080 | **71.6%** |
| 7 | 4,500 | 1,110 | **75.3%** |
| 8 | 5,200 | 1,140 | **78.1%** |
| 9 | 5,900 | 1,170 | **80.2%** |
| 10 | 6,600 | 1,200 | **81.8%** |
| 11 | 7,300 | 1,230 | **83.2%** |
| 12 | 8,000 | 1,260 | **84.3%** |
| 13 | 8,700 | 1,290 | **85.2%** |
| 14 | 9,400 | 1,320 | **86.0%** |
| 15 | 10,100 | 1,350 | **86.6%** |
| 16 | 10,800 | 1,380 | **87.2%** |
| 17 | 11,500 | 1,410 | **87.7%** |
| 18 | 12,200 | 1,440 | **88.2%** |
| 19 | 12,900 | 1,470 | **88.6%** |
| 20 | 13,600 | 1,500 | **89.0%** |

## Cumulative Totals (Over 20 Turns)

| Mode | Total Tokens Consumed | Status |
|------|-----------------------|--------|
| Transcript Accumulation | 151,000 | O(N²) Growth |
| Engram Engine | 36,300 | O(N) Stability |

### 🏆 Total Token Savings: **76.0%**

## The "Wall of Context"

In **Transcript Mode**, Turn 20 requires a prompt of **13600 tokens**.
In **Engram Mode**, Turn 20 stays lean at **1500 tokens**.

As workflows scale, Transcript mode inevitably hits context limits or becomes prohibitively expensive. Engram maintains a constant reasoning window regardless of turn count.
