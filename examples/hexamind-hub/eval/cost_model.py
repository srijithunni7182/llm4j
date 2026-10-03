#!/usr/bin/env python3
"""Rough cost model for evaluating Hexamind Hub with eval4j: Gemini runs the agents, Claude judges.

Every number is an ASSUMPTION you can change below; the point is the shape of the bill, not three significant
figures. Run `python3 cost_model.py` for the tables used in COST.md, or `--json` for machine-readable output.
Measure real token counts from your first run (the eval4j report's Cost page shows tokens per judge) and
replace the assumptions.
"""
import json, sys

# ---- prices, USD per 1M tokens (check before loading credits) -------------------------------------------
GEMINI = dict(name="gemini-3.5-flash", inp=1.50, out=9.00, cached=0.15)        # agents under test
JUDGES = {
    "claude-sonnet-5-5": dict(inp=2.00, out=10.00),   # main judge (recommended)
    "claude-haiku-4-5": dict(inp=1.00, out=5.00),     # cheap bulk judge, simple rubrics
    "claude-opus-5-5": dict(inp=4.00, out=20.00),     # calibration / cross-check judge
}
MAIN_JUDGE, CROSS_JUDGE = "claude-sonnet-5-5", "claude-opus-5-5"

# ---- Gemini: tokens per agent call ----------------------------------------------------------------------
THINKING_OUT_PER_CALL = 400      # billed as output; set 0 if you disable thinking for the agents
SYSTEM_TOKENS = 1800             # persona + constraints + tool schemas + current time, resent every iteration
OBS_TOKENS = 450                 # one search observation with fixtures (live search results are larger: ~1500)
ANSWER_OUT = 350                 # a final answer
STEP_OUT = 150                   # thought + action for a non-final iteration

def react_run(prompt_tokens, iterations, obs=OBS_TOKENS):
    """Tokens (in, out) for one ReAct run of `iterations` model calls; observations accumulate in context."""
    tin = tout = 0
    ctx = SYSTEM_TOKENS + prompt_tokens
    for i in range(iterations):
        tin += ctx
        last = i == iterations - 1
        tout += (ANSWER_OUT if last else STEP_OUT) + THINKING_OUT_PER_CALL
        if not last:
            ctx += STEP_OUT + obs
    return tin, tout

def gemini_cost(tin, tout):
    return (tin * GEMINI["inp"] + tout * GEMINI["out"]) / 1e6

def judge_cost(model, tin, tout):
    j = JUDGES[model]
    return (tin * j["inp"] + tout * j["out"]) / 1e6

# one judge call: system prompt + criteria + rubric + input + output (+ fixtures/trajectory)
def judge_call(extra_context=0):
    return 1500 + extra_context, 300          # tokens in, tokens out (JSON + short reasoning, low effort)

# ---- one full Hexamind debate (Gemini only) ---------------------------------------------------------------
def debate(shortened=False):
    tin = tout = calls = 0
    def run(prompt, iters):
        nonlocal tin, tout, calls
        a, b = react_run(prompt, iters); tin += a; tout += b; calls += iters
    for _ in range(6): run(300, 3.5 if False else 4)           # R1: fact-check search, ~4 iterations
    if shortened:
        tin += 3500; tout += 900 + THINKING_OUT_PER_CALL; calls += 2   # moderator + consensus
        return tin, tout, calls
    for _ in range(6): run(1800, 2)                            # R2: arguments with all R1 context
    for _ in range(6): run(1500, 2)                            # R3: critiques
    for _ in range(6): run(1500, 2)                            # R4: rebuttals
    for _ in range(6): run(1500, 2)                            # R5: responses
    for _ in range(6): run(7500, 2)                            # opinions read the whole discussion
    for _ in range(36):                                        # knowledge-triple extraction per substantial thought
        tin += 600; tout += 150; calls += 1
    tin += 4000; tout += 900 + THINKING_OUT_PER_CALL; calls += 1   # consensus
    return tin, tout, calls

DEBATE = debate(); DEBATE_SHORT = debate(True)

# ---- suites -------------------------------------------------------------------------------------------------
def reasoning_suite(n=48, judge_calls_per=3, samples=1, judge=MAIN_JUDGE):
    g_in = g_out = 0
    for i in range(n):
        iters = 3
        a, b = react_run(200, iters); g_in += a; g_out += b
    j_in, j_out = judge_call(700)
    jc = n * judge_calls_per * samples
    return dict(gemini=gemini_cost(g_in, g_out), judge=judge_cost(judge, jc * j_in, jc * j_out), judge_calls=jc, agent_calls=n * 3)

def prompt_suite(n=12, variants=2, samples=1, judge=MAIN_JUDGE):
    g_in = g_out = 0
    for i in range(n * variants):
        a, b = react_run(600, 2); g_in += a; g_out += b
    j_in, j_out = judge_call(500)
    jc = n * variants * samples + n * 2          # rubric per variant + pairwise in both orders
    return dict(gemini=gemini_cost(g_in, g_out), judge=judge_cost(judge, jc * j_in, jc * j_out), judge_calls=jc, agent_calls=n * variants * 2)

def flow_suite(n_full=7, n_short=3, judge_calls_per=4, samples=1, judge=MAIN_JUDGE):
    g = n_full * gemini_cost(DEBATE[0], DEBATE[1]) + n_short * gemini_cost(DEBATE_SHORT[0], DEBATE_SHORT[1])
    calls = n_full * DEBATE[2] + n_short * DEBATE_SHORT[2]
    j_in, j_out = judge_call(5500)               # judges read the consensus plus a transcript digest
    jc = (n_full + n_short) * judge_calls_per * samples
    return dict(gemini=g, judge=judge_cost(judge, jc * j_in, jc * j_out), judge_calls=jc, agent_calls=calls)

def total(parts):
    return dict(gemini=sum(p["gemini"] for p in parts), judge=sum(p["judge"] for p in parts),
                judge_calls=sum(p["judge_calls"] for p in parts), agent_calls=sum(p["agent_calls"] for p in parts))

def scale(t, f_agent, f_judge):
    return dict(gemini=t["gemini"] * f_agent, judge=t["judge"] * f_judge,
                judge_calls=round(t["judge_calls"] * f_judge), agent_calls=round(t["agent_calls"] * f_agent))

FULL = total([reasoning_suite(), prompt_suite(), flow_suite()])
PROFILES = {
    # name: (description, result)
    "FAST   (deterministic checks only; agents still run)": scale(FULL, 1.0, 0.0),
    "BUILD  (per PR: ~20% of cases changed, rest reused from the judge cache)": scale(FULL, 0.20, 0.20),
    "SAMPLE (20% seeded sample, every build or nightly)": scale(FULL, 0.20, 0.20),
    "FULL   (everything, judge once per case)": FULL,
    "FULL + calibration (3 judge samples, plus an Opus cross-check on 20% of judged cases)":
        total([reasoning_suite(samples=3), prompt_suite(samples=3), flow_suite(samples=3),
               scale(total([reasoning_suite(judge=CROSS_JUDGE), prompt_suite(judge=CROSS_JUDGE), flow_suite(judge=CROSS_JUDGE)]), 0.0, 0.2)]),
}

def money(x): return f"${x:,.2f}"

def table(mult_agent=1.0, mult_judge=1.0):
    rows = []
    for name, r in PROFILES.items():
        g, j = r["gemini"] * mult_agent, r["judge"] * mult_judge
        rows.append((name, r["agent_calls"], r["judge_calls"], g, j, g + j))
    return rows

# ---- the lean plan: what to do when rupees matter ------------------------------------------------------------
LITE = dict(inp=0.30, out=2.50)      # gemini-3.5-flash-lite, USD per 1M tokens (check ai.google.dev)

def with_model(fn, price):
    """Run a cost function with another Gemini price."""
    global GEMINI
    old = GEMINI
    GEMINI = dict(old, **price)
    try:
        return fn()
    finally:
        GEMINI = old

def lean(ma=1.0, mj=1.0):
    """Lean plan. Trajectory PATH logic is tested with a scripted (stub) model: free. Agent outputs are recorded
    and replayed, so a build only re-runs changed scenarios. Real debates are run 3 times per release
    (standard, debunk, refinement) instead of 10. Development uses Flash-Lite or the free tier."""
    def agent_suites():   # reasoning + prompts, real Gemini Flash, judged once by Sonnet
        r, p = reasoning_suite(), prompt_suite()
        return (r["gemini"] + p["gemini"]) * ma, (r["judge"] + p["judge"]) * mj
    def three_debates():
        g = (gemini_cost(*DEBATE[:2]) * 2 + gemini_cost(*DEBATE_SHORT[:2])) * ma     # standard, refinement, debunk
        jc = 3 * 4
        j_in, j_out = judge_call(5500)
        return g, judge_cost(MAIN_JUDGE, jc * j_in, jc * j_out) * mj
    ag, aj = agent_suites(); dg, dj = three_debates()
    full_g, full_j = ag + dg, aj + dj                      # a lean "release" run
    pr_g, pr_j = ag * 0.2, aj * 0.2                        # a PR build: 20% of agent scenarios changed (replay for the rest)
    lite_g = (with_model(lambda: reasoning_suite()["gemini"], LITE) + with_model(lambda: prompt_suite()["gemini"], LITE)) * ma
    setup_g = 2 * full_g + 3 * lite_g + 4 * pr_g
    setup_j = 2 * full_j + 3 * aj + 4 * pr_j + 0.10 * mj   # + judge calibration on 30 cases, 3 samples, Sonnet
    month_g = 4 * full_g + 10 * pr_g
    month_j = 4 * full_j + 10 * pr_j
    return dict(release=(full_g, full_j), pr=(pr_g, pr_j), setup=(setup_g, setup_j), month=(month_g, month_j))

def plan(ma=1.0, mj=1.0):
    """Credits to load: a setup phase, then a month of steady state, plus one prompt-optimizer campaign."""
    def t(name): r = PROFILES[name]; return r["gemini"] * ma + r["judge"] * mj
    full = t("FULL   (everything, judge once per case)")
    calib = t("FULL + calibration (3 judge samples, plus an Opus cross-check on 20% of judged cases)")
    build = t("BUILD  (per PR: ~20% of cases changed, rest reused from the judge cache)")
    sample = t("SAMPLE (20% seeded sample, every build or nightly)")
    # one optimizer campaign: ~150 rollouts of single-agent scenarios (agent run + one judge call each) plus rewriter calls
    a_in, a_out = react_run(200, 3)
    rollout = (gemini_cost(a_in, a_out) * ma) + judge_cost(MAIN_JUDGE, *judge_call(700)) * mj
    optimizer = 150 * rollout + 40 * judge_cost(MAIN_JUDGE, 3000, 800) * mj
    setup = 10 * full + 2 * calib + 3 * optimizer
    month = 30 * sample + 20 * build + 4 * full
    return dict(setup=setup, month=month, optimizer=optimizer, rollout=rollout)

if __name__ == "__main__":
    if "--json" in sys.argv:
        print(json.dumps({k: v for k, v in PROFILES.items()}, indent=2)); sys.exit()
    print(f"One full debate (Gemini): {DEBATE[2]} model calls, {DEBATE[0]:,} tokens in, {DEBATE[1]:,} out = {money(gemini_cost(DEBATE[0], DEBATE[1]))}")
    print(f"One shortened (debunk) debate: {DEBATE_SHORT[2]} calls = {money(gemini_cost(DEBATE_SHORT[0], DEBATE_SHORT[1]))}")
    for label, ma, mj in (("LOW   (0.7x tokens)", 0.7, 0.7), ("EXPECTED", 1.0, 1.0), ("HIGH  (1.7x tokens, live search results)", 1.7, 1.5)):
        print(f"\n{label}")
        print(f"{'profile':100s} {'agent calls':>11s} {'judge calls':>11s} {'Gemini':>9s} {'Claude':>9s} {'total':>9s}")
        for name, ac, jc, g, j, t in table(ma, mj):
            print(f"{name:100s} {ac:11d} {jc:11d} {money(g):>9s} {money(j):>9s} {money(t):>9s}")
        l = lean(ma, mj)
        print("  LEAN plan: setup %s (Gemini %s + Claude %s), month %s (Gemini %s + Claude %s); release run %s, PR build %s" % (
            money(sum(l["setup"])), money(l["setup"][0]), money(l["setup"][1]), money(sum(l["month"])), money(l["month"][0]), money(l["month"][1]),
            money(sum(l["release"])), money(sum(l["pr"]))))
        p = plan(ma, mj)
        print(f"  Setup phase (10 full runs, 2 calibration runs, 3 optimizer campaigns): {money(p['setup'])}")
        print(f"  One optimizer campaign (~150 rollouts): {money(p['optimizer'])}   (one rollout: {money(p['rollout'])})")
        print(f"  A month of steady state (30 nightly samples, 20 PR builds, 4 weekly full runs): {money(p['month'])}")
