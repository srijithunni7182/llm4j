"""Probe P1, LangGraph side: kill the process right after a send, resume, and count the sends.

usage: python probe.py run1|run2 SENDS_LOG DB [--key]
  run1: runs the graph; the process dies (os._exit) right after the send node's side effect.
  run2: resumes the same thread from the SQLite checkpoint.
  --key: the developer's own idempotency key (thread id + node) is written with the send, as the docs advise.
"""
import os
import sqlite3
import sys
from typing import TypedDict

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph


class State(TypedDict, total=False):
    receipt: str
    done: bool


def build(sends_log: str, use_key: bool, thread_id: str):
    def send(state: State) -> State:
        # the side effect: one line per send
        key = f"{thread_id}:send" if use_key else "-"
        with open(sends_log, "a") as f:
            f.write(key + "\n")
        if os.environ.get("CRASH_AFTER_SEND"):
            os._exit(137)  # the process dies before the node returns, so before the checkpoint is written
        return {"receipt": "sent"}

    def after(state: State) -> State:
        if os.environ.get("CRASH_LATE"):
            os._exit(137)  # the send node finished and its checkpoint was written
        return {"done": True}

    g = StateGraph(State)
    g.add_node("send", send)
    g.add_node("after", after)
    g.add_edge(START, "send")
    g.add_edge("send", "after")
    g.add_edge("after", END)
    return g


def main() -> None:
    mode, sends_log, db = sys.argv[1], sys.argv[2], sys.argv[3]
    use_key = "--key" in sys.argv
    thread_id = "probe-thread"
    conn = sqlite3.connect(db, check_same_thread=False)
    saver = SqliteSaver(conn)
    graph = build(sends_log, use_key, thread_id).compile(checkpointer=saver)
    config = {"configurable": {"thread_id": thread_id}}
    # LANGGRAPH_DURABILITY: exit | async | sync (the docs' durability modes); unset means the library's default
    extra = {"durability": os.environ["LANGGRAPH_DURABILITY"]} if os.environ.get("LANGGRAPH_DURABILITY") else {}
    if mode == "run1":
        graph.invoke({}, config, **extra)
    else:
        print("resumed ->", graph.invoke(None, config, **extra))


if __name__ == "__main__":
    main()
