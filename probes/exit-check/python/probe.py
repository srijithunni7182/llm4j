"""P11: a reply that breaks a written rule. Can it leave without a check, does anything notice before the run, and is an agent's own sending tool call checked?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes

BAD = "We will refund you in full."   # breaks the rule "never promise a refund"
rows = []
sends = []

def send_email(to: str, body: str) -> str:
    """Send an email."""
    sends.append(body)
    return "sent"

# ---- LangGraph -------------------------------------------------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.prebuilt import ToolNode
from langchain_core.messages import AIMessage

class S(TypedDict):
    reply: str
model = fakes.chat_model([BAD])
with fakes.Quiet() as q:
    g = StateGraph(S)
    g.add_node("write", lambda s: {"reply": model.invoke("question").content})
    g.add_edge(START, "write"); g.add_edge("write", END)
    out = g.compile().invoke({"reply": ""})
sends.clear()
from langgraph.graph import MessagesState
tg = StateGraph(MessagesState)
tg.add_node("tools", ToolNode([send_email])); tg.add_edge(START, "tools"); tg.add_edge("tools", END)
tg.compile().invoke({"messages": [AIMessage(content="", tool_calls=[{"name": "send_email", "args": {"to": "a@example.com", "body": BAD}, "id": "1"}])]})
rows.append(("LangGraph 1.2.18", f"A graph with a writer node and no check: the rule-breaking reply left as the graph's output = {out['reply'] == BAD}; building raised {len(q.messages)} warnings. A model's own sending tool call run through the prebuilt ToolNode was executed with nothing in between: sends = {len(sends)}."))

# ---- LangChain: create_agent and its tool-call middleware ------------------------------------------
from langchain.agents import create_agent
from langchain.agents.middleware import wrap_tool_call
import langchain.agents.middleware as mw
hooks = ", ".join(n for n in ("before_agent", "before_model", "wrap_model_call", "after_model", "wrap_tool_call", "after_agent") if hasattr(mw, n))
call = AIMessage(content="", tool_calls=[{"name": "send_email", "args": {"to": "a@example.com", "body": BAD}, "id": "1"}])
sends.clear()
a = create_agent(model=fakes.chat_model([call, "done"]), tools=[send_email])
a.invoke({"messages": [("user", "hi")]})
unchecked = len(sends)

@wrap_tool_call
def refuse_refund_promises(request, handler):
    if "refund" in str(request.tool_call["args"]).lower():
        from langchain_core.messages import ToolMessage
        return ToolMessage(content="blocked by policy", tool_call_id=request.tool_call["id"])
    return handler(request)

sends.clear()
a2 = create_agent(model=fakes.chat_model([call, "done"]), tools=[send_email], middleware=[refuse_refund_promises])
a2.invoke({"messages": [("user", "hi")]})
rows.append(("LangChain 1.4.7", f"create_agent: the model's sending tool call was executed with nothing in between: sends = {unchecked}. With a wrap_tool_call middleware that we wrote (a string test for 'refund'), the same call was blocked: sends = {len(sends)}. The hook exists and works; the rule, and the decision to install it, are code you write. Hooks exported by langchain.agents.middleware: {hooks}."))

# ---- ADK -------------------------------------------------------------------------------------------
from google.adk.agents import LlmAgent
from google.genai import types
fc = types.Part(function_call=types.FunctionCall(name="send_email", args={"to": "a@example.com", "body": BAD}))
sends.clear()
fakes.adk_run(LlmAgent(name="a", model=fakes.adk_model([fc, "done"]), instruction="x", tools=[send_email]), "hi")
adk_unchecked = len(sends)
def block(tool, args, tool_context):
    if "refund" in str(args).lower():
        return {"result": "blocked by policy"}
sends.clear()
fakes.adk_run(LlmAgent(name="a", model=fakes.adk_model([fc, "done"]), instruction="x", tools=[send_email], before_tool_callback=block), "hi")
rows.append(("Google ADK (Python) 2.13.0", f"LlmAgent: the model's sending tool call was executed with nothing in between: sends = {adk_unchecked}. With a before_tool_callback we wrote, the same call was blocked: sends = {len(sends)}. The package also ships a Model Armor integration (a Google Cloud screening service; not run here: it needs a Google Cloud project)."))

# ---- would anything notice before the run that no check stands before an exit? -----------------------
packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(no (check|guard|validation) (before|stands)|unchecked (output|reply|exit)|every (exit|output) .{0,20}(check|guard))")
rows.append(("Search of the installed packages", f"{count} files match a search for an analysis that a workflow has an exit with no check before it. Hooks (middleware, callbacks) are things you install; nothing reads the graph and reports where none is installed."))
fakes.report(rows)
