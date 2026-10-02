#!/usr/bin/env python3
"""Stand-ins for the rewind G5 runs: a model server (Ollama chat protocol) for the self-correcting report workflow, and a webhook that counts posts.

    rewind_services.py --port 8766 --log requests.jsonl [--hang AGENT[:feedback]] [--fail AGENT]
The model is a pure function of what it is asked: the collector echoes the feedback it was given, and the reviewer scores 3 until the draft
shows two rounds of feedback (fix2), then 9 -- so a run goes back twice and publishes.
--hang AGENT       that agent's call sleeps once it has an observation (or, with :feedback, once its task mentions that feedback)
--fail AGENT       that agent's calls answer HTTP 500
"""
import argparse
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
ARGS = None


def call(tool, args):
    return "```json\n" + json.dumps({"thought": f"use {tool}", "action": tool, "action_input": args}) + "\n```"


def done(text):
    return "```json\n" + json.dumps({"thought": "t", "final_answer": text}) + "\n```"


def reply(agent, task):
    if agent == "Collector":
        m = re.search(r"Feedback so far: (\S+)", task)
        return done("data(" + (m.group(1) if m else "?") + ")")
    if agent == "Analyst":
        return done("analysis of " + re.sub(r"[\r\n].*", "", re.sub(r"^.*Analyse ", "", task)))
    if agent == "Writer":
        return done("draft from " + re.sub(r"[\r\n].*", "", re.sub(r"^.*Write the report from ", "", task)))
    if agent == "Reviewer":
        m = re.search(r"fix(\d+)", task)
        rnd = int(m.group(1)) if m else 0
        return "```json\n" + json.dumps({"score": 3 if rnd < 2 else 9, "notes": f"fix{rnd + 1}"}) + "\n```"
    if agent == "Publisher":
        return call("Slack", {"title": "Report", "text": re.sub(r"[\r\n].*", "", task)[:80]}) if task.count("Observation:") == 0 else done("published")
    return done("ok")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def record(self, body):
        with LOCK, open(ARGS.log, "a") as f:
            f.write(json.dumps({"method": self.command, "path": self.path, "body": body}) + "\n")

    def send(self, code, body):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if not self.path.endswith("/chat"):
            self.record(body)  # the webhook
            return self.send(200, "ok")
        msgs = json.loads(body)["messages"]
        system, last = msgs[0]["content"], msgs[-1]["content"]
        mark = last.rfind("Current Task:")
        task = last[mark + len("Current Task:"):].strip() if mark >= 0 else last
        task = re.sub(r"^Question:\s*", "", task)  # the model is a pure function of the task, whichever way the agent frames it
        who = re.search(r"You are (\w+)", system)
        agent = who.group(1) if who else "?"
        self.record(json.dumps({"agent": agent, "task": task[:120]}))
        if ARGS.fail == agent:
            return self.send(500, "{}")
        if ARGS.hang:
            name, _, feedback = ARGS.hang.partition(":")
            if agent == name and ((feedback and feedback in task) or (not feedback and task.count("Observation:") >= 1)):
                time.sleep(120)  # the process is killed while the model "thinks"
        return self.send(200, json.dumps({"model": "fake", "message": {"role": "assistant", "content": reply(agent, task)}, "done": True,
                                          "prompt_eval_count": 10, "eval_count": 5}))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--hang", default="")
    ap.add_argument("--fail", default="")
    ARGS = ap.parse_args()
    open(ARGS.log, "w").close()
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    print("fake services on", ARGS.port, flush=True)
    server.serve_forever()
