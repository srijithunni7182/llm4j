from typing import TypedDict

from langchain_core.language_models import BaseChatModel
from langgraph.graph import END, START, StateGraph


class State(TypedDict, total=False):
    message: str
    kind: str
    result: str


def build(classifier: BaseChatModel, replier: BaseChatModel, escalator: BaseChatModel):
    def classify(state: State) -> State:
        label = classifier.invoke("Classify (order_status, refund or other): " + state["message"]).content.strip()
        return {"kind": label if label in ("order_status", "refund", "other") else "other"}

    def reply(state: State) -> State:
        return {"result": replier.invoke("Answer: " + state["message"]).content}

    def escalate(state: State) -> State:
        return {"result": escalator.invoke("Summarise for a person: " + state["message"]).content}

    g = StateGraph(State)
    g.add_node("classify", classify)
    g.add_node("reply", reply)
    g.add_node("escalate", escalate)
    g.add_edge(START, "classify")
    g.add_conditional_edges("classify", lambda s: "reply" if s["kind"] == "order_status" else "escalate")
    g.add_edge("reply", END)
    g.add_edge("escalate", END)
    return g.compile()
