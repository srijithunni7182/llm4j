#!/usr/bin/env python3
"""Stand-in model server (Ollama chat protocol) for the earned-autonomy G5 runs. The model is a pure function of what it is asked:
it proposes approve for an amount under 100 and reject otherwise; a system prompt containing APPROVE-EVERYTHING makes it approve everything.

    autonomy_services.py --port 8767 --log requests.jsonl [--hang TEXT]
--hang TEXT   a call whose task mentions TEXT sleeps (the process is killed while the model "thinks")
"""
import argparse
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
ARGS = None


def propose(system, task):
    m = re.search(r"amount\D{0,4}(\d+)", task)
    amount = int(m.group(1)) if m else 0
    choice = "approve" if ("APPROVE-EVERYTHING" in system or amount < 100) else "reject"
    return json.dumps({"choice": choice, "reasoning": f"amount {amount}", "confidence": 0.9})


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        msgs = json.loads(body)["messages"]
        system, last = msgs[0]["content"], msgs[-1]["content"]
        mark = last.rfind("Current Task:")
        task = last[mark + len("Current Task:"):].strip() if mark >= 0 else last
        with LOCK, open(ARGS.log, "a") as f:
            f.write(json.dumps({"path": self.path, "task": task[:200]}) + "\n")
        if ARGS.hang and ARGS.hang in task:
            time.sleep(120)
        out = json.dumps({"model": "fake", "message": {"role": "assistant", "content": "```json\n" + propose(system, task) + "\n```"}, "done": True,
                          "prompt_eval_count": 10, "eval_count": 5}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--hang", default="")
    ARGS = ap.parse_args()
    open(ARGS.log, "w").close()
    print("fake services on", ARGS.port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()
