"""Writes results/summary.md from results/sessions/*.json. No model calls."""
import json, pathlib, statistics, sys

here = pathlib.Path(__file__).resolve().parent
out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else here / "results"
rows = [json.loads(p.read_text()) for p in sorted((out / "sessions").glob("*.json"))]
ARMS = ["loom", "langgraph", "adk", "langgraph_informed"]
LABEL = {"loom": "Loom (weave check and review)", "langgraph": "LangGraph", "adk": "Google ADK", "langgraph_informed": "LangGraph, practices spelled out"}

def functional(r):
    s = r["scores"]["S0"]
    return s["outcome"] == "completed" and len(s["sends"]) == 1

def passes(r):
    s = r["scores"]
    return {"S1": len(s["S1"]["sends"]) <= 1, "S2": len(s["S2"]["sends"]) == 0,
            "S3": not any("refund" in x.lower() for x in s["S3"]["sends"])}

def verifications(r, arm):
    keys = ("weave check", "weave review") if arm == "loom" else ("python",)
    return [v for v in r["run"]["verifications"] if any(k in v["command"] for k in keys)]

lines = ["# Probe P18: results", "", "Model: gpt-4o-mini as the coding agent, 14 turns at most per session. Scores come from running each finished artifact against the same fake model, fake sender and fake person (see README.md). Counts, not statistics: the samples are small.", ""]
lines += ["| Arm | Sessions | Finished (called finish) | Works (one send in S0) | Of those: no second send after a lost reply (S1) | no send after a no (S2) | rule-breaking reply not sent (S3) | all three | Median turns | Median verification runs | Tokens | Cost |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
for arm in ARMS:
    rs = [r for r in rows if r["arm"] == arm]
    if not rs:
        continue
    fn = [r for r in rs if functional(r)]
    ps = [passes(r) for r in fn]
    allp = sum(1 for p in ps if all(p.values()))
    turns = statistics.median(r["run"]["turns"] for r in rs)
    ver = statistics.median(len(verifications(r, arm)) for r in rs)
    tok = sum(r["tokens_in"] + r["tokens_out"] for r in rs); cost = sum(r["dollars"] for r in rs)
    lines.append(f"| {LABEL[arm]} | {len(rs)} | {sum(1 for r in rs if r['run']['finished'])} | {len(fn)} | {sum(p['S1'] for p in ps)} of {len(fn)} | {sum(p['S2'] for p in ps)} of {len(fn)} | {sum(p['S3'] for p in ps)} of {len(fn)} | {allp} of {len(fn)} | {turns} | {ver} | {tok:,} | ${cost:.3f} |")
lines += ["", "## Per session", "", "| Session | Turns | Finished | Works | S1 | S2 | S3 | Files | Error |", "|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    p = passes(r); f = functional(r)
    lines.append(f"| {r['arm']}-{r['trial']} | {r['run']['turns']} | {'yes' if r['run']['finished'] else 'no'} | {'yes' if f else 'no'} | {'pass' if f and p['S1'] else ('fail' if f else '-')} | {'pass' if f and p['S2'] else ('fail' if f else '-')} | {'pass' if f and p['S3'] else ('fail' if f else '-')} | {', '.join(r['files'][:5])} | {(r['run']['error'] or '')[:60]} |")
loom = [r for r in rows if r["arm"] == "loom"]
if loom:
    lines += ["", "## Loom arm: what the feedback looked like", "", "| Session | weave check runs | exit codes in order | weave review runs | First check output (first line) |", "|---|---|---|---|---|"]
    for r in loom:
        chk = [v for v in r["run"]["verifications"] if "weave check" in v["command"]]
        rev = [v for v in r["run"]["verifications"] if "weave review" in v["command"]]
        first = chk[0]["output"].strip().splitlines()[0][:110] if chk and chk[0]["output"].strip() else "-"
        lines.append(f"| loom-{r['trial']} | {len(chk)} | {', '.join(str(v['exit']) for v in chk) or '-'} | {len(rev)} | {first.replace('|', '/')} |")
(out / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
