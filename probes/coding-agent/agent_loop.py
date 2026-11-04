"""A small coding-agent loop for probe P18: a model with a file editor and a short list of allowed commands (no shell), a turn limit and a hard spend cap.

The model is called through the OpenAI chat API with function calling (standard library only). The commands it can run
get no API key in their environment.
"""
import json, os, pathlib, subprocess, time, urllib.request

MODEL = "gpt-4o-mini"
PRICE_IN, PRICE_OUT = 0.15 / 1e6, 0.60 / 1e6          # dollars per token for gpt-4o-mini
OUTPUT_CUT = 3000
def tools_for(commands):
    """The agent's tools. There is no shell: it can write and read files in its folder and run only the commands in
    `commands` (each one a fixed program with one file or topic argument, started without a shell)."""
    return [
        {"type": "function", "function": {"name": "run_command", "description": "Run one allowed command in the working folder. Allowed: " + ", ".join(commands),
            "parameters": {"type": "object", "properties": {"command": {"type": "string", "enum": list(commands)}, "argument": {"type": "string", "description": "a file name in the working folder, or a topic for 'weave man'"}},
                           "required": ["command", "argument"]}}},
        {"type": "function", "function": {"name": "write_file", "description": "Write a text file (path relative to the working folder).",
            "parameters": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}}},
        {"type": "function", "function": {"name": "read_file", "description": "Read a text file (path relative to the working folder).",
            "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
        {"type": "function", "function": {"name": "finish", "description": "Call this when the work is finished.",
            "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"]}}},
    ]


class SpendCap(Exception):
    pass


class Spend:
    """Total tokens and dollars across every session, kept on disk so a restart cannot reset it."""

    def __init__(self, path, max_tokens, max_dollars):
        self.path, self.max_tokens, self.max_dollars = pathlib.Path(path), max_tokens, max_dollars
        self.tokens_in = self.tokens_out = 0
        if self.path.exists():
            d = json.loads(self.path.read_text())
            self.tokens_in, self.tokens_out = d["tokens_in"], d["tokens_out"]

    @property
    def dollars(self):
        return self.tokens_in * PRICE_IN + self.tokens_out * PRICE_OUT

    def check(self):
        if self.tokens_in + self.tokens_out >= self.max_tokens or self.dollars >= self.max_dollars:
            raise SpendCap(f"cap reached: {self.tokens_in + self.tokens_out} tokens, ${self.dollars:.3f}")

    def add(self, usage):
        self.tokens_in += usage["prompt_tokens"]; self.tokens_out += usage["completion_tokens"]
        self.path.write_text(json.dumps({"tokens_in": self.tokens_in, "tokens_out": self.tokens_out, "dollars": round(self.dollars, 4)}))


def chat(messages, spend, tools):
    spend.check()
    body = json.dumps({"model": MODEL, "messages": messages, "tools": tools, "temperature": 0.5}).encode()
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})
    for attempt in range(3):
        try:
            r = json.load(urllib.request.urlopen(req, timeout=120))
            spend.add(r["usage"])
            return r["choices"][0]["message"]
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 2:
                time.sleep(2 ** attempt * 3)
                continue
            raise RuntimeError(f"model call failed: {e.code}")
    raise RuntimeError("model call failed")


def cut(text):
    return text if len(text) <= OUTPUT_CUT else text[:OUTPUT_CUT] + "\n... (cut)"


def inside(work, rel):
    p = (work / rel).resolve()
    if work.resolve() not in p.parents and p != work.resolve():
        raise ValueError("path outside the working folder")
    return p


def run_session(system, task, work, run_command, commands, spend, max_turns=14):
    """Run one session. Returns a dict with the transcript of tool calls and the verification runs."""
    work = pathlib.Path(work); work.mkdir(parents=True, exist_ok=True)
    tools = tools_for(commands)
    messages = [{"role": "system", "content": system}, {"role": "user", "content": task}]
    events, verifications, finished, turns, error = [], [], False, 0, None
    try:
        while turns < max_turns and not finished:
            turns += 1
            msg = chat(messages, spend, tools)
            messages.append(msg)
            calls = msg.get("tool_calls") or []
            if not calls:
                messages.append({"role": "user", "content": "Continue working, or call finish if you are done."})
                events.append({"turn": turns, "tool": None, "note": (msg.get("content") or "")[:200]})
                continue
            for call in calls:
                name = call["function"]["name"]
                try:
                    args = json.loads(call["function"]["arguments"] or "{}")
                except json.JSONDecodeError:
                    args = {}
                if name == "run_command":
                    command, argument = str(args.get("command", "")), str(args.get("argument", ""))
                    out, code = run_command(command, argument)
                    result = cut(f"exit {code}\n{out}")
                    shown = f"{command} {argument}"
                    events.append({"turn": turns, "tool": "run_command", "command": shown[:300], "exit": code})
                    verifications.append({"turn": turns, "command": shown[:300], "exit": code, "output": out[:1500]})
                elif name == "write_file":
                    try:
                        path = inside(work, str(args.get("path", "")))
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text(str(args.get("content", "")))
                        result = f"wrote {args.get('path')}"
                    except Exception as e:
                        result = f"error: {e}"
                    events.append({"turn": turns, "tool": "write_file", "path": str(args.get("path"))})
                elif name == "read_file":
                    try:
                        result = cut(inside(work, str(args.get("path", ""))).read_text())
                    except Exception as e:
                        result = f"error: {e}"
                    events.append({"turn": turns, "tool": "read_file", "path": str(args.get("path"))})
                elif name == "finish":
                    finished = True
                    result = "ok"
                    events.append({"turn": turns, "tool": "finish", "summary": str(args.get("summary"))[:300]})
                else:
                    result = "unknown tool"
                messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
    except SpendCap as e:
        error = str(e)
    except Exception as e:  # recorded in the result, never hidden
        error = f"{type(e).__name__}: {e}"
    return {"turns": turns, "finished": finished, "error": error, "events": events, "verifications": verifications}
