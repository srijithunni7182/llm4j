"""Probe P5, LangGraph side: a person must approve before a send; the process ends while the question is open;
a later process resumes the same thread with the answer.

usage: python probe.py ask SENDS DB   |   python probe.py answer SENDS DB yes"""
import sqlite3
import sys
from typing import TypedDict

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt


class State(TypedDict, total=False):
    approval: str
    result: str


def build(sends: str):
    def approve(state: State) -> State:
        return {"approval": interrupt("Approve sending the refund? (yes/no)")}

    def send(state: State) -> State:
        with open(sends, "a") as f:
            f.write("sent\n")
        return {"result": "sent"}

    def not_sent(state: State) -> State:
        return {"result": "not sent"}

    g = StateGraph(State)
    g.add_node("approve", approve)
    g.add_node("send", send)
    g.add_node("not_sent", not_sent)
    g.add_edge(START, "approve")
    g.add_conditional_edges("approve", lambda s: "send" if s["approval"] == "yes" else "not_sent")
    g.add_edge("send", END)
    g.add_edge("not_sent", END)
    return g


def main() -> None:
    mode, sends, db = sys.argv[1], sys.argv[2], sys.argv[3]
    saver = SqliteSaver(sqlite3.connect(db, check_same_thread=False))
    graph = build(sends).compile(checkpointer=saver)
    config = {"configurable": {"thread_id": "probe-thread"}}
    if mode == "ask":
        out = graph.invoke({}, config)
        print("returned to the caller:", out.get("__interrupt__"))
    else:
        print("resumed ->", graph.invoke(Command(resume=sys.argv[4]), config))


if __name__ == "__main__":
    main()
