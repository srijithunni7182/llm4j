#!/usr/bin/env python3
"""
Sub-phase 8d: print each vendor's RAW grounded response (design question Q9), so the citation mapping is built from real payloads and not from memory.

A throwaway script, kept as evidence: for every vendor whose key is in the environment it sends one tiny question that needs current information,
with the vendor's web search switched on, once as a whole response and once streamed, plus one variant that uses the search options we plan to map
(domains, recency, a cap on searches). Responses are scrubbed of keys and ids (the same rules as the smoke runner), printed, and saved here.

    python3 capture.py                 # every vendor with a key; SMOKE_ONLY=perplexity,openai limits it
    python3 capture.py --list          # what it would call, without calling anything

Keys: OPENAI_API_KEY, XAI_API_KEY, PERPLEXITY_API_KEY, ANTHROPIC_API_KEY (or SPIKE_ANTHROPIC_KEY), GEMINI_API_KEY (or SMOKE_GEMINI_API_KEY).
Spend: a few cents in all (each search is billed per request, $2.50 to $14 per 1,000); every call is capped by max output tokens and by SPEND_CAP_USD
(default 0.30) against the prices below. A vendor without a key, or one that answers 4xx for billing or permissions, is recorded and skipped; nothing is retried.
"""
import json, os, re, sys, urllib.error, urllib.request, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
QUESTION = "In one sentence: what is the Reserve Bank of India's current policy repo rate, and when was it last changed?"
CAP = float(os.environ.get("SPEND_CAP_USD", "0.30"))

# USD per million tokens (input, output) and per search, read from the vendors' pages on 2026-10-22; used only for the cap
PRICES = {
    "perplexity": (0.10, 0.50, 0.0025),
    "openai": (0.10, 0.50, 0.010),
    "xai": (1.25, 2.50, 0.005),
    "anthropic": (0.10, 0.50, 0.010),
    "gemini": (0.75, 3.75, 0.014),
}

def key(*names):
    for n in names:
        v = os.environ.get(n, "").strip()
        if v:
            return v
    return None

VENDORS = {
    "perplexity": dict(
        key=key("PERPLEXITY_API_KEY"), url="https://api.perplexity.ai/v1/agent", auth="bearer",
        variants={
            "plain": {"model": "openai/gpt-6-luna", "input": QUESTION, "max_output_tokens": 500,
                      "tools": [{"type": "web_search", "max_results": 3}]},
            "options": {"model": "openai/gpt-6-luna", "input": QUESTION, "max_output_tokens": 500,
                        "tools": [{"type": "web_search", "max_results": 3,
                                   "filters": {"search_domain_filter": ["rbi.org.in"], "search_recency_filter": "year"},
                                   "user_location": {"country": "IN"}}]},
            "cap": {"model": "openai/gpt-6-luna", "input": QUESTION, "max_output_tokens": 500, "max_steps": 2,
                    "tools": [{"type": "web_search", "max_results": 3}]},
        }),
    "openai": dict(
        key=key("OPENAI_API_KEY"), url="https://api.openai.com/v1/responses", auth="bearer",
        variants={
            "plain": {"model": "gpt-6-luna", "input": QUESTION, "max_output_tokens": 800, "reasoning": {"effort": "low"},
                      "tools": [{"type": "web_search"}], "include": ["web_search_call.action.sources"]},
            "options": {"model": "gpt-6-luna", "input": QUESTION, "max_output_tokens": 800, "reasoning": {"effort": "low"}, "max_tool_calls": 2,
                        "tools": [{"type": "web_search", "filters": {"allowed_domains": ["rbi.org.in"]},
                                   "user_location": {"type": "approximate", "country": "IN"}}],
                        "include": ["web_search_call.action.sources"]},
        }),
    "xai": dict(
        key=key("XAI_API_KEY"), url="https://api.x.ai/v1/responses", auth="bearer",
        variants={
            "plain": {"model": "grok-4.3", "input": QUESTION, "max_output_tokens": 800, "tools": [{"type": "web_search"}]},
            "options": {"model": "grok-4.3", "input": QUESTION, "max_output_tokens": 800,
                        "tools": [{"type": "web_search", "filters": {"allowed_domains": ["rbi.org.in"]}}]},
        }),
    "anthropic": dict(
        key=key("ANTHROPIC_API_KEY", "SPIKE_ANTHROPIC_KEY"), url="https://api.anthropic.com/v1/messages", auth="x-api-key",
        variants={
            "plain": {"model": "claude-haiku-5-5", "max_tokens": 800, "messages": [{"role": "user", "content": QUESTION}],
                      "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 2}]},
            "options": {"model": "claude-haiku-5-5", "max_tokens": 800, "messages": [{"role": "user", "content": QUESTION}],
                        "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 1, "allowed_domains": ["rbi.org.in"],
                                   "user_location": {"type": "approximate", "country": "IN"}}]},
        }),
    "gemini": dict(
        key=key("GEMINI_API_KEY", "SMOKE_GEMINI_API_KEY"), url="https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent",
        stream_url="https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:streamGenerateContent?alt=sse", auth="x-goog-api-key",
        variants={
            "plain": {"contents": [{"role": "user", "parts": [{"text": QUESTION}]}], "tools": [{"googleSearch": {}}],
                      "generationConfig": {"maxOutputTokens": 800}},
        }),
}

PREFIXED = re.compile(r"\b(chatcmpl|cmpl|resp|msg|rs|fc|call|req|ws|wsc|toolu|srvtoolu|run|gen|item)([-_])[A-Za-z0-9][A-Za-z0-9_-]{7,}")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
FIELDS = re.compile(r'("(?:user_id|account_id|organization|organization_id|org_id|project|project_id|request_id|safety_identifier|responseId)"\s*:\s*)"[^"]*"')
FINGERPRINT = re.compile(r'("system_fingerprint"\s*:\s*)"[^"]*"')
KEYSHAPE = re.compile(r"\b(sk|xai|pplx|key)-[A-Za-z0-9_-]{16,}")

def scrub(text, secret):
    if secret and len(secret) >= 6:
        text = text.replace(secret, "SCRUBBED-KEY")
    text = FINGERPRINT.sub(r'\1"fp_scrubbed"', text)
    text = FIELDS.sub(r'\1"scrubbed"', text)
    text = UUID.sub("00000000-0000-0000-0000-000000000000", text)
    text = PREFIXED.sub(r"\1\2scrubbed", text)
    return KEYSHAPE.sub(r"\1-SCRUBBED-KEY", text)

def call(name, spec, variant, body, stream):
    url = spec.get("stream_url") if stream and spec.get("stream_url") else spec["url"]
    payload = dict(body)
    if stream and spec["auth"] != "x-goog-api-key":
        payload["stream"] = True
    headers = {"Content-Type": "application/json"}
    if spec["auth"] == "bearer":
        headers["Authorization"] = "Bearer " + spec["key"]
    elif spec["auth"] == "x-api-key":
        headers["x-api-key"] = spec["key"]
        headers["anthropic-version"] = "2023-06-01"
    else:
        headers["x-goog-api-key"] = spec["key"]
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, r.read().decode("utf-8", "replace"), payload
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), payload

def main():
    only = [s for s in os.environ.get("SMOKE_ONLY", "").split(",") if s]
    listing = "--list" in sys.argv
    spent = 0.0
    log = ["# Grounding payloads, captured %s\n" % datetime.date.today()]
    for name, spec in VENDORS.items():
        if only and name not in only:
            continue
        if not spec["key"]:
            log.append("- **%s**: not captured (no key in the environment)\n" % name)
            print("[%s] no key; skipped" % name)
            continue
        for variant, body in spec["variants"].items():
            for stream in (False, True) if variant == "plain" else (False,):
                label = "%s-%s%s" % (name, variant, "-stream" if stream else "")
                if listing:
                    print("would call", label)
                    continue
                if spent >= CAP:
                    log.append("- **%s**: stopped, spend cap reached\n" % label)
                    continue
                status, text, sent = call(name, spec, variant, body, stream)
                text = scrub(text, spec["key"])
                ext = "sse" if stream else "json"
                with open(os.path.join(HERE, label + "." + ext), "w") as f:
                    f.write(text if stream else (json.dumps(json.loads(text), indent=2, ensure_ascii=False) if text.strip().startswith(("{", "[")) else text))
                with open(os.path.join(HERE, label + ".request.json"), "w") as f:
                    json.dump(sent, f, indent=2)
                in_p, out_p, search_p = PRICES[name]
                # an estimate for the cap only: bytes/4 as tokens, one search per call
                spent += len(json.dumps(sent)) / 4 / 1e6 * in_p + len(text) / 4 / 1e6 * max(in_p, out_p) + search_p
                print("=== %s: HTTP %d, %d bytes ===" % (label, status, len(text)))
                print(text)
                log.append("- **%s**: HTTP %d, %d bytes\n" % (label, status, len(text)))
                if status in (401, 402, 403):
                    log.append("  - billing or permission problem: %s. Skipping the rest of this vendor.\n" % text[:200].replace("\n", " "))
                    break
    if not listing:
        log.append("\nEstimated spend for the cap: US$%.3f\n" % spent)
        with open(os.path.join(HERE, "CAPTURE_LOG.md"), "w") as f:
            f.write("".join(log))

main()
