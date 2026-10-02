#!/usr/bin/env python3
"""Stand-ins for G5, one process, three jobs, so the real `weave` can run the digest end to end with no accounts:

  /api/chat              a model server speaking Ollama's chat protocol, with a playbook for the digest agents
  /v0/...                a news API (Hacker News shape)
  /hook                  a webhook that records every request (headers and body) in <log>

    fake_services.py --port 8765 --log requests.jsonl [--hang AGENT]   # --hang: that agent's 2nd step sleeps (to be killed)
    fake_services.py --port 8765 --log requests.jsonl --replies replies.json   # scripted replies per agent (R3)
"""
import argparse
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
ARGS = None
REPLIES = {}
DAY = {"n": 1}


def call(tool, args):
    return "```json\n" + json.dumps({"thought": f"use {tool}", "action": tool, "action_input": args}) + "\n```"


def done(text):
    return "```json\n" + json.dumps({"thought": "t", "final_answer": text}) + "\n```"


def title(task):
    found = re.findall(r"Alpha story, day \d+", task)
    return found[-1] if found else "nothing new"


def playbook(system, task):
    """What each digest agent says next, from its role and how many tool results are already in its task."""
    seen = task.count("Observation:")
    if "You are Collector" in system:
        return [call("Notes", {"action": "read", "path": "last.json"}), call("Hn", {"path": "/v0/topstories.json"}),
                call("Hn", {"path": "/v0/item/101.json"})][seen] if seen < 3 else done("NEW: " + title(task))
    if "You are Writer" in system:
        t = title(task)
        steps = [call("Notes", {"action": "write", "path": "digest.md", "content": "# Digest\n" + t}),
                 call("Notes", {"action": "write", "path": "last.json", "content": json.dumps({"reported": [t]})})]
        return steps[seen] if seen < 2 else done("Digest: " + t)
    if "You are Notifier" in system:
        return call("Outbox", {"subject": "Daily digest", "body": title(task)}) if seen == 0 else done("sent")
    if "You are Pinger" in system:
        return call("Slack", {"title": "Digest", "text": title(task)}) if seen == 0 else done("posted")
    return done("ok")


def scripted(system, task):
    """R3: replies from a file, per agent name, consumed in order; anything after the list is a final answer."""
    m = re.search(r"You are (\w+)", system)
    agent = m.group(1) if m else "?"
    seen = task.count("Observation:")
    steps = REPLIES.get(agent, [])
    return call(steps[seen]["tool"], steps[seen]["args"]) if seen < len(steps) else done("finished")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def record(self, body):
        with LOCK, open(ARGS.log, "a") as f:
            f.write(json.dumps({"method": self.command, "path": self.path, "headers": dict(self.headers), "body": body}) + "\n")

    def send(self, code, body, ctype="application/json"):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.record("")
        if self.path == "/v0/topstories.json":
            return self.send(200, "[101,102]")
        if self.path == "/v0/item/101.json":
            return self.send(200, json.dumps({"id": 101, "title": f"Alpha story, day {DAY['n']}"}))
        if self.path.startswith("/echo"):  # reflects the request headers, as a misbehaving API might
            return self.send(200, json.dumps({"you_sent": dict(self.headers)}))
        if self.path == "/day/2":
            DAY["n"] = 2
            return self.send(200, "{}")
        self.send(404, "not found", "text/plain")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if self.path.endswith("/chat"):
            req = json.loads(body)
            msgs = req["messages"]
            system, task = msgs[0]["content"], msgs[-1]["content"]
            who = re.search(r"You are (\w+)", system)
            self.record(json.dumps({"agent": who.group(1) if who else "?", "observations": task.count("Observation:")}))
            if ARGS.hang and f"You are {ARGS.hang}" in system and task.count("Observation:") >= 1:
                time.sleep(120)  # the process is killed while the model "thinks"
            reply = scripted(system, task) if REPLIES else playbook(system, task)
            return self.send(200, json.dumps({"model": "fake", "message": {"role": "assistant", "content": reply}, "done": True,
                                              "prompt_eval_count": 10, "eval_count": 5}))
        self.record(body)  # the webhook
        if "/error" in self.path:  # a failing webhook that echoes what it was sent, including the URL it was sent to
            return self.send(500, f"failed for {self.path} with {dict(self.headers)} body {body}", "text/plain")
        self.send(200, "ok", "text/plain")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--hang", default="")
    ap.add_argument("--replies", default="")
    ARGS = ap.parse_args()
    if ARGS.replies:
        REPLIES.update(json.load(open(ARGS.replies)))
    open(ARGS.log, "w").close()
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    print("fake services on", ARGS.port, flush=True)
    server.serve_forever()
