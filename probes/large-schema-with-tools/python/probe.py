"""P10: a step that uses tools and must answer in a large nested schema. Does anything notice before the run?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from typing import List, Optional
from pydantic import BaseModel

class Line(BaseModel):
    sku: str
    qty: int
    price: float

class Address(BaseModel):
    street: str
    city: str
    country: str

class Order(BaseModel):
    customer: str
    email: str
    shipping: Address
    billing: Address
    lines: List[Line]
    notes: Optional[str] = None
    priority: int
    gift: bool

def lookup(sku: str) -> str:
    """Look up a product."""
    return "widget"

rows = []

# ---- LangChain create_agent ---------------------------------------------------------------------
from langchain.agents import create_agent
from langchain_core.tools import tool
with fakes.Quiet() as q:
    agent = create_agent(model=fakes.chat_model(["x"]), tools=[tool(lookup)], response_format=Order)
rows.append(("LangChain 1.4.7", f"create_agent with one tool and response_format=Order (nested objects, a list of objects, {len(Order.model_fields)} top-level fields) built with {len(q.messages)} warnings and was accepted."))

# ---- LangGraph: a node that binds tools and asks for the schema -----------------------------------
from langgraph.graph import StateGraph, START, END
from typing import TypedDict
class S(TypedDict):
    text: str
with fakes.Quiet() as q2:
    g = StateGraph(S)
    model = fakes.chat_model(["x"])
    g.add_node("work", lambda s: {"text": str(model.bind_tools([lookup]).invoke(s["text"]).content)})
    g.add_edge(START, "work"); g.add_edge("work", END); g.compile()
rows.append(("LangGraph 1.2.18", f"A node that binds a tool to a model and is expected to answer in Order: built and compiled with {len(q2.messages)} warnings. The schema is not part of the graph; the node holds it in its own code."))

# ---- ADK ------------------------------------------------------------------------------------------
from google.adk.agents import LlmAgent
note = ""
try:
    with fakes.Quiet() as q3:
        a = LlmAgent(name="a", model=fakes.adk_model(["{}"]), instruction="x", tools=[lookup], output_schema=Order)
    note = f"LlmAgent(tools=[lookup], output_schema=Order) was accepted with {len(q3.messages)} warnings."
except Exception as e:  # recorded, not hidden
    note = f"LlmAgent(tools=[...], output_schema=Order) was refused when built: {type(e).__name__}: {str(e)[:160]}"
rows.append(("Google ADK (Python) 2.13.0", note))

# ---- is there any analysis that looks at schema size or reasoning + schema? -----------------------
packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(schema (is )?too (large|complex|big)|nested schema|too many (fields|properties)|reason(ing)? .{0,40}structured output)")
rows.append(("Search of the installed packages", f"{count} files match a search for a warning about large or nested schemas, or about reasoning and a schema together."))
fakes.report(rows)
