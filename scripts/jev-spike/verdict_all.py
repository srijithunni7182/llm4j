#!/usr/bin/env python3
"""Applies the verdict rule of TEST_STRATEGY 5.4 to every non-chat contender in a results CSV, against the cheap chat model of the same run.
    python3 verdict_all.py results/<stamp>.csv"""
import csv, math, statistics, sys
from collections import defaultdict
PRICE = {"jev": 0.042, "pplx:pplx-decider-latest": 0.02, "pplx:pplx-decider-flash-latest": 0.01}
CHEAP = ("cheap:claude-haiku-5-5", (0.10, 0.50))

def ece(rows, bins=10):
    rows = [r for r in rows if r["conf"] not in ("", None)]
    e = 0.0
    for b in range(bins):
        inb = [r for r in rows if b / bins <= float(r["conf"]) < (b + 1) / bins or (b == bins - 1 and float(r["conf"]) == 1.0)]
        if inb:
            e += len(inb) / len(rows) * abs(sum(r["correct"] == "True" for r in inb) / len(inb) - statistics.mean(float(r["conf"]) for r in inb))
    return e

def p95(v):
    v = sorted(v)
    return v[min(len(v) - 1, int(math.ceil(.95 * len(v))) - 1)]

rows = list(csv.DictReader(open(sys.argv[1])))
by = defaultdict(list)
for r in rows:
    by[(r["task"], r["contender"])].append(r)
print("| task | contender | accuracy | ECE | p95 ms | $/1,000 | vs cheap: accuracy | ECE | latency or cost 3x | recommended |")
print("|---|---|---|---|---|---|---|---|---|---|")
for task in ("intent", "safety", "judge"):
    cheap = by.get((task, CHEAP[0]))
    if not cheap:
        continue
    first = lambda rs: [r for r in rs if r["rep"] == "0"]
    ca = statistics.mean(r["correct"] == "True" for r in first(cheap))
    cp = p95([float(r["ms"]) for r in cheap if float(r["ms"]) > 0])
    cc = statistics.mean(int(r["in_tok"]) * CHEAP[1][0] + int(r["out_tok"]) * CHEAP[1][1] for r in cheap) / 1e6 * 1000
    for (t, name), rs in sorted(by.items()):
        if t != task or name not in PRICE:
            continue
        acc = statistics.mean(r["correct"] == "True" for r in first(rs))
        e = ece(first(rs)); pj = p95([float(r["ms"]) for r in rs if float(r["ms"]) > 0])
        cost = statistics.mean(int(r["in_tok"]) * PRICE[name] for r in rs) / 1e6 * 1000
        a_ok, e_ok = acc >= ca - 0.02, e <= 0.10
        l_ok = cp >= 3 * pj or cc >= 3 * cost
        print(f"| {task} | {name} | {acc:.3f} | {e:.3f} | {pj:.0f} | {cost:.4f} | {acc:.3f} vs {ca:.3f} {'PASS' if a_ok else 'FAIL'} | {'PASS' if e_ok else 'FAIL'} | {'PASS' if l_ok else 'FAIL'} | {'YES' if a_ok and e_ok and l_ok else 'NO'} |")
