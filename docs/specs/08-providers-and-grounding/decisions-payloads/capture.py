#!/usr/bin/env python3
"""
Sub-phase 8k: record what Perplexity's Decisions API really answers, so PerplexityDecisionsProvider is tested on a real response and not only on the reference.

A throwaway script, kept as evidence. Two calls: the quickstart request (a short review, three questions: noul, choice, score; about 400 input tokens, so about
US$0.00001 at $0.02 per million) and one request with a misspelt model, which is refused with 400 and costs nothing. Key: PERPLEXITY_API_KEY (never printed or saved).
    python3 capture.py
"""
import json, os, re, sys, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.environ.get("PERPLEXITY_API_KEY", "").strip()
URL = "https://api.perplexity.ai/v1/decisions"
STATE = {"title": "Battery died after two weeks",
         "review": "The headphones sound great, but the battery stopped charging after two weeks."}
QUESTIONS = {
    "defect": {"type": "noul", "instructions": "Does the review report a product defect?"},
    "sentiment": {"type": "choice", "instructions": "What is the overall sentiment of the review?",
                  "criteria": {"positive": "Mostly satisfied", "mixed": "Praise and complaints in one review", "negative": None}},
    "severity": {"type": "score", "instructions": "How severe is the reported problem?",
                 "criteria": ["Cosmetic", "Inconvenient", "Product unusable"]},
}
CASES = {
    "ok": {"model": "pplx-decider-flash-latest", "state": STATE, "questions": QUESTIONS},
    "bad_model": {"model": "pplx-decidr-latest", "state": STATE, "questions": QUESTIONS},
}

def scrub(text):
    if KEY:
        text = text.replace(KEY, "SCRUBBED-KEY")
    text = re.sub(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b", "00000000-0000-0000-0000-000000000000", text)
    return re.sub(r"\bpplx-[A-Za-z0-9]{20,}", "pplx-SCRUBBED-KEY", text)

def main():
    if not KEY:
        print("no PERPLEXITY_API_KEY; nothing captured")
        return
    for name, body in CASES.items():
        req = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, text = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode()
        text = scrub(text)
        try:
            text = json.dumps(json.loads(text), indent=2, ensure_ascii=False)
        except ValueError:
            pass
        with open(os.path.join(HERE, name + ".request.json"), "w") as f:
            json.dump(body, f, indent=2)
        with open(os.path.join(HERE, name + ".response.json"), "w") as f:
            f.write(text)
        with open(os.path.join(HERE, name + ".status"), "w") as f:
            f.write(str(status))
        print("=== %s: HTTP %d ===" % (name, status))
        print(text)

main()
