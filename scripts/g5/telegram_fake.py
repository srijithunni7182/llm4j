#!/usr/bin/env python3
"""A stand-in for the Telegram Bot API for the remote-answers G5 runs: sendMessage and getUpdates (offset, timeout), plus two control endpoints.

    telegram_fake.py --port 8768 --log messages.jsonl --token 123456:FAKE
POST /_reply  {"chat": 5550001, "from": 5550001, "text": "...", "reply_to": 901}   makes a reply arrive (the person typing in the chat)
GET  /_sent                                                                         every message the bot sent, as JSON lines
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
UPDATES = []
SENT = []
STATE = {"msg": 900, "upd": 100}
ARGS = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send_json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/_sent":
            with LOCK:
                data = "\n".join(json.dumps(s) for s in SENT).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_json(404, {"ok": False})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/_reply":
            with LOCK:
                STATE["msg"] += 1
                STATE["upd"] += 1
                msg = {"message_id": STATE["msg"], "date": int(time.time()), "from": {"id": body.get("from", body["chat"])}, "chat": {"id": body["chat"]}, "text": body["text"]}
                if body.get("reply_to"):
                    msg["reply_to_message"] = {"message_id": body["reply_to"]}
                UPDATES.append({"update_id": STATE["upd"], "message": msg})
            return self.send_json(200, {"ok": True})
        prefix = "/bot" + ARGS.token + "/"
        if not self.path.startswith(prefix):
            return self.send_json(401, {"ok": False, "description": "Unauthorized"})
        method = self.path[len(prefix):]
        if method == "sendMessage":
            with LOCK:
                STATE["msg"] += 1
                entry = {"message_id": STATE["msg"], "chat": body["chat_id"], "text": body["text"], "parse_mode": body.get("parse_mode")}
                SENT.append(entry)
                with open(ARGS.log, "a") as f:
                    f.write(json.dumps(entry) + "\n")
            return self.send_json(200, {"ok": True, "result": {"message_id": entry["message_id"], "chat": {"id": body["chat_id"]}}})
        if method == "getUpdates":
            offset = body.get("offset", 0)
            deadline = time.time() + min(body.get("timeout", 0), 2)
            while True:
                with LOCK:
                    out = [u for u in UPDATES if u["update_id"] >= offset]
                if out or time.time() >= deadline:
                    return self.send_json(200, {"ok": True, "result": out})
                time.sleep(0.1)
        self.send_json(404, {"ok": False, "description": "Not Found"})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--token", required=True)
    ARGS = ap.parse_args()
    open(ARGS.log, "w").close()
    print("fake telegram on", ARGS.port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()
