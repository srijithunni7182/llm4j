#!/usr/bin/env python3
"""Generates the sample run bundles used for the documentation screenshots.

The data is invented and deterministic (seeded). It follows spec/01-RUN-FORMAT.md and is validated by the
schema contract test. Usage: python3 generate.py <output-root>
"""
import hashlib, json, os, random, shutil, sys
from datetime import datetime, timedelta, timezone

out = sys.argv[1] if len(sys.argv) > 1 else "bundles"
shutil.rmtree(out, ignore_errors=True)

def hex16(t): return hashlib.sha256(t.encode()).hexdigest()[:16]
def case_id(k): return "c_" + hex16("case\0" + k)
def U(*parts): return int(hashlib.sha256("|".join(parts).encode()).hexdigest()[:12], 16) / float(16**12)
def key(ck, m, occ=0): return "k_" + hex16(ck + "\0" + m + "\0" + str(occ))

NOW = datetime(2026, 10, 3, 10, 14, tzinfo=timezone.utc)
def iso(dt): return dt.strftime("%Y-%m-%dT%H:%M:%SZ")

# ---------------------------------------------------------------- dataset
CATS = {
    "refund": ["Refund inside the window", "Refund outside the window", "Refund for a damaged item", "Partial refund", "Refund to store credit", "Refund after exchange"],
    "shipping": ["Shipping to Canada", "Express shipping cost", "Track a delayed parcel", "Change the delivery address", "Customs fees", "Lost in transit"],
    "account": ["Reset a password", "Change the account email", "Delete my account", "Two-factor setup", "Merge duplicate accounts", "Update payment method"],
    "billing": ["Invoice copy", "Double charge", "Tax on invoice", "Subscription renewal date", "Cancel a subscription", "Currency conversion"],
    "privacy": ["GDPR data export", "Cookie preferences", "Who sees my data", "Delete order history", "Marketing opt-out", "Data retention period"],
    "handoff": ["Angry customer, hand over", "Legal threat", "Fraud report", "Accessibility request", "Press enquiry", "Large order discount"],
}
scenarios = []
for cat, names in CATS.items():
    for i, name in enumerate(names):
        sid = cat + "-" + str(i + 1)
        dims = ["correctness", "relevancy", "grounding", "efficiency", "reasoning"]
        if cat in ("privacy", "handoff"): dims.append("safety")
        if cat == "privacy" and i < 2: dims.append("compliance")
        if cat in ("refund", "shipping"): dims.append("retrieval")
        scenarios.append(dict(id=sid, caseId=case_id(sid), datasetId="support-scenarios", name=name,
                              input="Customer question: " + name.lower() + "?", dimensions=dims, families=["agents"], tags=[cat]))
convs = []
for i, name in enumerate(["Order change over three turns", "Escalation after repeated failure", "Politeness under pressure", "Remembers the order number", "Switches language mid-chat"]):
    sid = "conv-%d" % (i + 1)
    convs.append(dict(id=sid, caseId=case_id(sid), datasetId="support-conversations", name=name, input="(multi-turn) " + name, dimensions=["conversation", "safety"], families=["conversations"], tags=["conversation"]))
wfs = [dict(id="wf-publish", caseId=case_id("wf-publish"), datasetId="support-workflows", name="Research and publish: sufficient findings", input="Publish the Q3 FAQ", dimensions=["orchestration"], families=["workflows"], tags=["workflow"]),
       dict(id="wf-escalate", caseId=case_id("wf-escalate"), datasetId="support-workflows", name="Escalate a fraud report", input="Handle a fraud report", dimensions=["orchestration", "safety"], families=["workflows"], tags=["workflow"])]
ab_cases = []
for i, name in enumerate(["Greeting tone", "Refund explanation", "Shipping delay apology", "Policy citation", "Handoff wording", "Closing message", "Short answers", "Plain language"]):
    sid = "ab-%d" % (i + 1)
    ab_cases.append(dict(id=sid, caseId=case_id(sid), datasetId="support-scenarios", name="Prompt A/B: " + name, input="Rewrite the answer for: " + name.lower(), dimensions=["prompting"], families=["prompts"], tags=["ab"]))
ALL = scenarios + convs + wfs + ab_cases

METRICS = {
    "answer-correctness": dict(name="Answer Correctness", kind="JUDGE", family="agents", facet="answers", dimension="correctness", threshold=0.7, judgeId="judge-main"),
    "answer-relevancy": dict(name="Answer Relevancy", kind="JUDGE", family="agents", facet="answers", dimension="relevancy", threshold=0.7, judgeId="judge-main"),
    "faithfulness": dict(name="Faithfulness", kind="JUDGE", family="agents", facet="answers", dimension="grounding", threshold=0.7, judgeId="judge-main"),
    "toxicity": dict(name="Toxicity", kind="JUDGE", family="agents", facet="answers", dimension="safety", threshold=0.8, judgeId="judge-main"),
    "contextual-precision": dict(name="Contextual Precision", kind="JUDGE", family="retrieval", facet="rag", dimension="retrieval", threshold=0.7, judgeId="judge-main"),
    "latency-p95": dict(name="Latency p95", kind="MEASURED", family="agents", facet="answers", dimension="efficiency", unit="s", budget=4.0),
    "token-budget": dict(name="Token budget", kind="MEASURED", family="agents", facet="answers", dimension="efficiency", unit="tokens", budget=3000.0),
    "tool-order": dict(name="Tool Order", kind="ASSERTION", family="agents", facet="tools", dimension="reasoning", threshold=1),
    "knowledge-retention": dict(name="Knowledge Retention", kind="JUDGE", family="conversations", facet="multi", dimension="conversation", threshold=0.7, judgeId="judge-main"),
    "role-adherence": dict(name="Role Adherence", kind="JUDGE", family="conversations", facet="multi", dimension="safety", threshold=0.8, judgeId="judge-main"),
    "correct-branch": dict(name="Correct branch taken", kind="ASSERTION", family="workflows", facet="orchestration", dimension="orchestration", threshold=1),
    "follows-expected-path": dict(name="Follows expected path", kind="ASSERTION", family="workflows", facet="trajectory", dimension="orchestration", threshold=1),
    "prompt-v3-vs-v2": dict(name="support-agent v3 vs v2", kind="PAIRWISE", family="prompts", facet="compare", dimension="prompting", threshold=0.5, judgeId="judge-main"),
}
for k, m in METRICS.items(): m["id"] = k

# ---------------------------------------------------------------- run plan
# per-dimension "true quality" per run (probability a case passes) and drift
RUNS = [
    dict(id="01JAEXV0MAIN000000141", n=141, branch="main", commit="c41a9b2", age=4, profile="FULL", q=dict(correctness=.90, relevancy=.95, grounding=.93, retrieval=.88, efficiency=.80, safety=.97, reasoning=.92, conversation=.85, orchestration=1.0, prompting=.5)),
    dict(id="01JAFXV9H3FEATURE000144", n=144, branch="feature/refund-policy", commit="a1b2c3d", age=2, profile="BUILD", q=dict(correctness=.88, relevancy=.95, grounding=.86, retrieval=.84, efficiency=.82, safety=.97, reasoning=.92, conversation=.85, orchestration=1.0, prompting=.6)),
    dict(id="01JAFXV9H3FEATURE000147", n=147, branch="feature/refund-policy", commit="3ab91d0", age=1, profile="BUILD", q=dict(correctness=.88, relevancy=.93, grounding=.80, retrieval=.80, efficiency=.86, safety=.97, reasoning=.90, conversation=.85, orchestration=1.0, prompting=.7)),
    dict(id="01JAGXW2K7CANDIDATE000148", n=148, branch="feature/refund-policy", commit="9f2c4e1", age=0, profile="BUILD", q=dict(correctness=.84, relevancy=.93, grounding=.70, retrieval=.78, efficiency=.90, safety=.97, reasoning=.92, conversation=.80, orchestration=.5, prompting=.75)),
]
SCORE_MEAN_PASS, SCORE_MEAN_FAIL = 0.86, 0.45

JUDGES_ENV = lambda stats: [dict(id="judge-main", provider="google", model="gemini-2.5-pro", temperature=0.0, samples=3, aggregation="MEAN", rubricId="judge-rubrics", rubricVersion="1.4", stats=stats)]
AGENT_ENV = lambda ver: [dict(id="support-agent", provider="ollama", model="llama3.3:70b", promptId="support-agent.md", promptVersion="support-agent " + ver, tools=["order_lookup", "kb_search", "refund", "handoff"])]

def evals_for(run, rnd, carried_from=None):
    """Returns (evaluations, tests, stats) for one run."""
    q = run["q"]; seq = 0; ev = []; tests = []
    judge_calls = judge_hits = 0
    ts = lambda m: iso(NOW - timedelta(days=run["age"]) + timedelta(seconds=m))
    def add(c, mid, passed, score=None, **kw):
        nonlocal seq, judge_calls, judge_hits
        m = METRICS[mid]
        if "measured" in kw:
            pass
        e = dict(seq=seq, key=key(c["id"], mid), caseId=c["caseId"], scenarioId=c["id"], testId="com.acme.SupportBotEvalTest#" + c["id"], metric=mid,
                 kind=m["kind"], status="EVALUATED", passed=passed, source="FRESH", timestamp=ts(seq))
        if score is not None: e["score"] = round(score, 2)
        if "threshold" in m and m.get("threshold") is not None: e["threshold"] = m["threshold"]
        e.update(kw); seq += 1; ev.append(e); return e
    for c in scenarios:
        dims = c["dimensions"]
        bad_ground = rnd.random() > q["grounding"]
        for mid, dim in (("answer-correctness", "correctness"), ("answer-relevancy", "relevancy"), ("faithfulness", "grounding"), ("toxicity", "safety"), ("contextual-precision", "retrieval")):
            if dim not in dims: continue
            u = U(c["id"], mid); ok = u < q[dim]; thr = METRICS[mid]["threshold"]
            if ok: sc = thr + .02 + (1 - u / q[dim]) * (.97 - thr - .02)
            else: sc = thr - .03 - ((u - q[dim]) / max(.01, 1 - q[dim])) * (thr - .2)
            sc = min(1, max(0, sc + rnd.gauss(0, .015)))
            sc = max(sc, thr + .01) if ok else min(sc, thr - .01)
            reason = ("Matches the policy and the order data." if ok else {"correctness": "States a window that does not match the policy.", "relevancy": "Answers a different question than the one asked.", "grounding": "Makes a claim no retrieved chunk supports.", "safety": "Tone is dismissive.", "retrieval": "The relevant chunk is ranked below irrelevant ones."}[dim])
            reused = rnd.random() < .35 and run["profile"] == "BUILD"
            samples = [round(min(1, max(0, sc + rnd.gauss(0, .03))), 2) for _ in range(3)]
            kw = dict(reason=reason, judgeId="judge-main", samples=samples, durationMs=int(rnd.gauss(1900, 400)),
                      input=c["input"], actualOutput=("We accept returns within 14 days of delivery." if ok else "Returns are accepted within 30 days, no questions asked."),
                      expectedOutput="Returns are accepted within 14 days of delivery.",
                      retrievalContext=["Policy: 14 day return window from delivery.", "Shipping takes 3 days."] if dim in ("grounding", "retrieval", "correctness") else None)
            if reused:
                kw["source"] = "REUSED"; kw["calls"] = 0; judge_hits += 3
            else:
                kw["calls"] = 3; kw["tokensIn"] = 1200 + rnd.randint(-200, 300); kw["tokensOut"] = 160 + rnd.randint(-30, 40); kw["costUsd"] = round(kw["tokensIn"] * 1.25e-6 + kw["tokensOut"] * 10e-6, 4); judge_calls += 3
            if kw["retrievalContext"] is None: del kw["retrievalContext"]
            add(c, mid, ok, sc, **kw)
        if "efficiency" in dims:
            u = U(c["id"], "latency-p95"); ok = u < q["efficiency"]
            lat = round(1.2 + 2.6 * (u / q["efficiency"]) if ok else 4.2 + 1.8 * ((u - q["efficiency"]) / max(.01, 1 - q["efficiency"])), 1)
            add(c, "latency-p95", ok, 0.8 if ok else 0.3, reason="ok" if ok else "Over the 4 s budget.", measured=dict(value=lat, unit="s", budget=4.0), display="%.1f s" % lat)
            u2 = U(c["id"], "token-budget"); ok2 = u2 < min(.98, q["efficiency"] + .05)
            tok = int(1200 + 1600 * u2 if ok2 else 3100 + 1100 * u2)
            add(c, "token-budget", ok2, 0.8 if ok2 else 0.3, measured=dict(value=tok, unit="tokens", budget=3000.0), display="%d tokens" % tok)
        if "reasoning" in dims:
            ok = U(c["id"], "tool-order") < q["reasoning"]
            add(c, "tool-order", ok, 1.0 if ok else 0.0, reason=None if ok else "Expected tools [order_lookup, refund] in order but the agent used: [refund, order_lookup]")
        tests.append(dict(testId="com.acme.SupportBotEvalTest#" + c["id"], suite="com.acme.SupportBotEvalTest", name=c["name"], caseId=c["caseId"], scenarioId=c["id"],
                          status="PASSED" if all(e["passed"] for e in ev if e["caseId"] == c["caseId"] and e["status"] == "EVALUATED") else "FAILED", durationMs=rnd.randint(800, 2600)))
    for c in convs:
        for mid, dim in (("knowledge-retention", "conversation"), ("role-adherence", "safety")):
            ok = U(c["id"], mid) < q[dim]
            sc = min(1, max(0, rnd.gauss(.88 if ok else .5, .06)))
            sc = max(sc, .75) if ok else min(sc, .65)
            add(c, mid, ok, sc, reason="Keeps the order number across turns." if ok else "Forgets the order number after turn 3.", judgeId="judge-main",
                samples=[round(sc + d, 2) for d in (-.02, 0, .03)], calls=3, tokensIn=2400, tokensOut=210, costUsd=0.0051, durationMs=2400, input=c["input"])
            judge_calls += 3
        tests.append(dict(testId="com.acme.SupportBotEvalTest#" + c["id"], suite="com.acme.SupportBotEvalTest", name=c["name"], caseId=c["caseId"], scenarioId=c["id"], status="PASSED", durationMs=2200))
    for c in wfs:
        good = run["q"]["orchestration"] >= .99 or c["id"] != "wf-publish"
        add(c, "correct-branch", good, 1.0 if good else 0.0, reason=None if good else "Expected node n2 to take branch then but decisions there were: [else]")
        add(c, "follows-expected-path", good, 1.0 if good else 0.0, reason=None if good else "Expected the workflow to follow [start, n1, n2, n3, n4, n6, end] but it took [start, n1, n2, n5, n6, end]")
        tests.append(dict(testId="com.acme.WorkflowEvalTest#" + c["id"], suite="com.acme.WorkflowEvalTest", name=c["name"], caseId=c["caseId"], scenarioId=c["id"], status="PASSED" if good else "FAILED", durationMs=4100))
    wins = run["q"]["prompting"]
    for c in ab_cases:
        r = U(c["id"], "ab")
        b_win = r < wins - .15; tie = (not b_win) and r < wins + .05
        sc = 1.0 if b_win else .5 if tie else 0.0
        A = "Hello! Our returns policy lets you send items back within 14 days."
        B = "Hi there, thanks for asking. You can return any item within 14 days of delivery, and I can start the return for you now."
        add(c, "prompt-v3-vs-v2", sc >= .5, sc, reason="B is warmer and offers the next step." if b_win else "Both are fine; B adds nothing important." if tie else "A is more concise and equally complete.",
            judgeId="judge-main", calls=2, tokensIn=1500, tokensOut=120, costUsd=.0031, durationMs=2100, input=c["input"], actualOutput="A: " + A + "\n\nB: " + B)
        judge_calls += 2
        tests.append(dict(testId="com.acme.PromptCompareTest#" + c["id"], suite="com.acme.PromptCompareTest", name=c["name"], caseId=c["caseId"], scenarioId=c["id"], status="PASSED", durationMs=1900))
    return ev, tests, judge_calls, judge_hits

def agent_traces():
    t = []
    def steps(spec):
        out = []
        for i, (th, act, inp, obs, oc, d) in enumerate(spec, 1):
            out.append(dict(index=i, thought=th, action=act, input=inp, observation=obs, outcome=oc, durationMs=d))
        return out
    s = scenarios
    t.append(dict(traceId="t_refund", caseId=s[0]["caseId"], type="AGENT_STEPS", stepBudget=6, steps=steps([
        ("I need the order first.", "order_lookup", '{"order":"88207"}', "Order 88207: delivered 6 days ago.", "EXECUTED", 420),
        ("Check the policy.", "kb_search", '{"q":"refund window"}', "Policy: 14 days from delivery.", "EXECUTED", 310),
        ("Search the web too.", "web_search", '{"q":"refund policy"}', "No such tool.", "UNKNOWN_TOOL", 5),
        ("Reply to the customer.", "final_answer", "{}", "Replied.", "EXECUTED", 900)])))
    t.append(dict(traceId="t_ship", caseId=s[6]["caseId"], type="AGENT_STEPS", stepBudget=6, steps=steps([
        ("Look up the parcel.", "order_lookup", '{"order":"77120"}', "In transit, 2 days late.", "EXECUTED", 380),
        ("Look up the parcel again.", "order_lookup", '{"order":"77120"}', "Blocked: identical call repeated.", "DUPLICATE_BLOCKED", 2),
        ("Find the carrier policy.", "kb_search", '{"q":"delayed parcel"}', "Carrier SLA 5 days.", "EXECUTED", 290),
        ("Offer a goodwill refund.", "refund", '{"amount":5}', "Rejected by reviewer.", "REJECTED_BY_HUMAN", 15000),
        ("Apologise and explain.", "final_answer", "{}", "Replied.", "EXECUTED", 700)])))
    t.append(dict(traceId="t_gdpr", caseId=s[24]["caseId"], type="AGENT_STEPS", stepBudget=6, steps=steps([
        ("Find the export procedure.", "kb_search", '{"q":"gdpr export"}', "Export from Settings > Privacy.", "EXECUTED", 300),
        ("Trigger the export.", "account_export", '{"user":"u1"}', "Tool error: timeout.", "EXECUTION_ERROR", 5000),
        ("Retry the export.", "account_export", '{"user":"u1"}', "Queued.", "EXECUTED", 600),
        ("Reply.", "final_answer", "{}", "Replied.", "EXECUTED", 600)])))
    return t

def workflow_trace(candidate):
    nodes = [("start", "start", "Start", None, None), ("n1", "delegate", "delegate Researcher", "Researcher", None), ("n2", "alt", "findings SUFFICIENT?", None, None),
             ("n3", "loop", "loop until approved", None, 3), ("n4", "delegate", "delegate Writer", "Writer", None), ("n5", "delegate", "delegate Researcher", "Researcher", None),
             ("n6", "handoff", "handoff Publisher", "Publisher", None), ("end", "end", "End", None, None)]
    edges = [("start", "n1", None), ("n1", "n2", None), ("n2", "n3", "then"), ("n2", "n5", "else"), ("n3", "n4", None), ("n4", "n3", "again"), ("n3", "n6", None), ("n5", "n6", None), ("n6", "end", None)]
    expected = ["start", "n1", "n2", "n3", "n4", "n3", "n4", "n6", "end"]
    actual = ["start", "n1", "n2", "n3", "n4", "n3", "n4", "n6", "end"] if not candidate else ["start", "n1", "n2", "n5", "n6", "end"]
    events = [(0.0, "delegate_start", "Researcher", None), (4.1, "delegate_end", "Researcher", None)]
    if candidate:
        events += [(4.2, "decision", None, "else (inferred)"), (4.3, "delegate_start", "Researcher", None), (7.9, "delegate_end", "Researcher", None), (8.0, "guard", None, "pii guard applied"),
                   (8.1, "delegate_start", "Publisher", None), (9.0, "delegate_end", "Publisher", None)]
    else:
        events += [(4.2, "decision", None, "then (inferred)"), (4.3, "delegate_start", "Writer", None), (9.0, "delegate_end", "Writer", None), (9.1, "checkpoint", None, "draft-1"), (9.2, "delegate_start", "Writer", None),
                   (13.5, "delegate_end", "Writer", None), (13.6, "rewind", None, "rewound to draft-1"), (13.7, "delegate_start", "Publisher", None), (14.6, "delegate_end", "Publisher", None)]
    ev = []
    for t, ty, ag, tx in events:
        e = dict(t=t, type=ty); 
        if ag: e["agent"] = ag
        if tx: e["text"] = tx
        ev.append(e)
    spend = [dict(agent="Researcher", model="gemini-2.5-flash", promptTokens=5200, completionTokens=900, calls=3, costUsd=0.012),
             dict(agent="Writer", model="gemini-2.5-pro", promptTokens=8800, completionTokens=2100, calls=4, costUsd=0.044),
             dict(agent="Publisher", model="gemini-2.5-flash", promptTokens=1100, completionTokens=200, calls=1, costUsd=0.002)]
    if candidate: spend = [spend[0], spend[2]]
    return dict(traceId="t_wf" + ("_c" if candidate else "_p"), caseId=wfs[0]["caseId"], type="WORKFLOW",
                workflow=dict(name="ResearchAndPublish", graph=dict(nodes=[dict(id=a, kind=b, label=c, **({"agent": d} if d else {}), **({"bound": e} if e else {})) for a, b, c, d, e in nodes], edges=[dict(**{"from": a, "to": b}, **({"label": c} if c else {})) for a, b, c in edges]),
                              expectedPath=expected, actualPath=actual, events=ev, spend=spend, budgetUsd=0.2, rewinds=0 if candidate else 1, rewindCap=20))

def optimizer():
    rounds = [dict(index=0, action="BASELINE", bestScore=.62)]
    best = .62
    for i, (act, cand) in enumerate([("REJECTED", .64), ("ACCEPTED", .68), ("REJECTED", .66), ("ACCEPTED", .73), ("REJECTED", .72), ("ACCEPTED", .79), ("REJECTED", .78), ("ACCEPTED", .86)], 1):
        if act == "ACCEPTED": best = cand
        rounds.append(dict(index=i, action=act, candidateScore=cand, bestScore=best))
    return dict(id="opt-17", promptId="support-agent.md", fromVersion="v2", toVersion="v3", goal=.85, stopReason="goal reached in round 8", rounds=rounds,
                budget=dict(calls=74, maxCalls=120, costUsd=.88, maxCostUsd=1.5), overfit=dict(trainScore=.89, validationScore=.86, gap=.03, limit=.05),
                diff=[dict(op="DEL", text="You are a helpful support agent."), dict(op="ADD", text="You are a support agent for Acme Store. Answer from the knowledge base only."),
                      dict(op="KEEP", text="Always cite the help-centre article you used."), dict(op="ADD", text="If the policy does not cover the question, hand over to a person.")])

def write(root, rid, header, evs, scs, tests, traces, opts):
    d = os.path.join(root, "runs", rid); os.makedirs(d, exist_ok=True)
    json.dump(header, open(os.path.join(d, "run.json"), "w"), indent=2)
    for name, items in (("evaluations", evs), ("scenarios", scs), ("tests", tests), ("traces", traces), ("optimizations", opts)):
        if items:
            with open(os.path.join(d, name + ".jsonl"), "w") as f:
                for it in items: f.write(json.dumps(it) + "\n")

def header(run, rid, evs, calls, hits, group=None, datasets=None, agents_ver="v3", extra=None):
    counted = [e for e in evs if e["status"] == "EVALUATED"]
    src = {}
    for e in evs: src[e["source"]] = src.get(e["source"], 0) + 1
    cost = round(sum(e.get("costUsd", 0) for e in evs), 4)
    h = dict(schemaVersion=1, format="eval4j-run", runId=rid, runNumber=run["n"], status="COMPLETE",
             startedAt=iso(NOW - timedelta(days=run["age"])), endedAt=iso(NOW - timedelta(days=run["age"]) + timedelta(minutes=2, seconds=41)), durationMs=161000,
             project=dict(name="Support agent"), source=dict(branch=run["branch"], commit=run["commit"], dirty=False, ci=dict(provider="jenkins", buildNumber=str(run["n"]))),
             profile=dict(name=run["profile"], judgeBudgetUsd=2.0, carryOver=True),
             env=dict(agents=AGENT_ENV("v3" if run["age"] <= 1 else "v2"), judges=JUDGES_ENV(dict(calls=calls, cacheHits=hits, latencyMeanMs=1900, latencyP95Ms=3400, tokensIn=calls * 400, tokensOut=calls * 55, costUsd=cost, failures=0, retries=0)),
                      datasets=datasets or [dict(id="support-scenarios", name="support-scenarios.yaml", path="src/test/resources/support-scenarios.yaml", revision="12" if run["age"] < 1 else "11", hash=hashlib.sha256((str(run["age"] < 1)).encode()).hexdigest()[:32], scenarioCount=len(scenarios))],
                      eval4jVersion="5.1", javaVersion="17.0.12", configHash="6c760e2d9f59a7ab"),
             metrics=[dict(m) for k, m in METRICS.items() if any(e["metric"] == k for e in evs)],
             summary=dict(evaluations=len(evs), passed=sum(1 for e in counted if e["passed"]), failed=sum(1 for e in counted if not e["passed"]), notEvaluated=sum(1 for e in evs if e["status"] == "NOT_EVALUATED"), errors=0, bySource=src, costUsd=cost))
    if group: h["groupId"] = group
    return h

for idx, run in enumerate(RUNS):
    rnd = random.Random(1000 + idx)
    evs, tests, calls, hits = evals_for(run, rnd)
    candidate = idx == len(RUNS) - 1
    if candidate:
        # a cheap BUILD run: some cases were not judged and carry the previous result; one dimension has declared-but-unevaluated cases
        prev, _, _, _ = evals_for(RUNS[idx - 1], random.Random(1000 + idx - 1))
        prev_by = {e["key"]: e for e in prev}
        n = 0
        for e in evs:
            if e["kind"] == "JUDGE" and e["source"] == "FRESH" and e["metric"] in ("toxicity", "contextual-precision") and n < 9 and e["key"] in prev_by:
                p = prev_by[e["key"]]
                for k in ("reason", "samples", "calls", "tokensIn", "tokensOut", "costUsd", "durationMs"): e.pop(k, None)
                e.update(source="CARRIED", evaluatedInRun=RUNS[idx - 1]["id"], evaluatedAt=p["timestamp"], passed=p["passed"], score=p.get("score"), reason=p.get("reason"))
                n += 1
        # compliance scenarios: judged metric skipped (NOT_EVALUATED) under the BUILD budget
        for e in [x for x in evs if x["scenarioId"] in ("privacy-1", "privacy-2") and x["metric"] == "answer-correctness"]:
            e["status"] = "NOT_EVALUATED"; e["reason"] = "Not judged: the judge budget for this build was reached."
            for k in ("passed", "score", "samples", "calls", "tokensIn", "tokensOut", "costUsd", "durationMs", "reason"):
                if k != "reason": e.pop(k, None)
        for i, e in enumerate(evs): e["seq"] = i
    traces = []
    if idx >= 1:
        traces = agent_traces() + [workflow_trace(candidate)]
        trace_for = {t["caseId"]: t["traceId"] for t in traces}
        for e in evs:
            if e["caseId"] in trace_for: e["traceId"] = trace_for[e["caseId"]]
    opts = [optimizer()] if candidate else []
    if candidate:
        # split the candidate into two bundles of one build (two Maven modules sharing a groupId)
        group = "build-148"
        agent_ev = [e for e in evs if e["caseId"] not in {c["caseId"] for c in wfs + ab_cases + convs}]
        other_ev = [e for e in evs if e["caseId"] in {c["caseId"] for c in wfs + ab_cases + convs}]
        a_ids = {c["caseId"] for c in scenarios}
        h1 = header(run, run["id"] + "-agents", agent_ev, calls, hits, group=group, datasets=[dict(id="support-scenarios", name="support-scenarios.yaml", path="src/test/resources/support-scenarios.yaml", revision="12", hash="44f4cdb2f1d8a9575e4a4501365904b9", scenarioCount=len(scenarios))])
        h2 = header(run, run["id"] + "-flows", other_ev, 12, 0, group=group, datasets=[dict(id="support-workflows", name="support-workflows.yaml", path="flows/src/test/resources/support-workflows.yaml", revision="3", hash="9ae17aa1e2f04d4f9d3a2f0a9de1b001", scenarioCount=len(wfs + convs + ab_cases))])
        write(out, run["id"] + "-agents", h1, agent_ev, scenarios, [t for t in tests if t["caseId"] in a_ids], [t for t in traces if t["type"] == "AGENT_STEPS"], [])
        write(out, run["id"] + "-flows", h2, other_ev, wfs + convs + ab_cases, [t for t in tests if t["caseId"] not in a_ids], [t for t in traces if t["type"] == "WORKFLOW"], opts)
    else:
        h = header(run, run["id"], evs, calls, hits, datasets=[dict(id="support-scenarios", name="support-scenarios.yaml", path="src/test/resources/support-scenarios.yaml", revision="11", hash="44f4cdb2f1d8a9575e4a4501365904b9", scenarioCount=len(ALL))])
        write(out, run["id"], h, evs, ALL, tests, traces, [])
print("wrote", out)
