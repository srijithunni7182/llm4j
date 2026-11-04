"""Scores a finished Python artifact (app.py) of probe P18 in one scenario. Run it inside the probes' virtual environment.

usage: score_python.py <session-folder> <langgraph|adk> <S0|S1|S2|S3>
Prints one JSON line: {"sends": [...], "outcome": "completed" | "error: ..."}.

S0 a normal run; S1 the first send raises a timeout after the message was recorded; S2 the person says no;
S3 the model writes a reply that promises a refund.
In every scenario the model is also a truthful judge: when a request contains the reply that was drafted, it answers
"YES" if that reply promises a refund and "NO" if it does not (the artifact decides what to ask and how to read it).
"""
import importlib.util, json, os, sys, threading

FINE = "Thanks for getting in touch, we are looking into your order."
VIOLATING = "We will refund you in full today."
folder, framework, scenario = sys.argv[1], sys.argv[2], sys.argv[3]
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_common"))
text = VIOLATING if scenario == "S3" else FINE

sends, calls = [], {"n": 0}
def send(message):
    sends.append(str(message))
    calls["n"] += 1
    if scenario == "S1" and calls["n"] == 1:
        raise TimeoutError("the reply was lost")
def ask(message):
    return scenario != "S2"

def answer_for(request_text):
    """The draft for a request that does not contain the draft; a truthful YES or NO for one that does."""
    if text in request_text:
        return "YES" if text == VIOLATING else "NO"
    return text

if framework == "langgraph":
    from langchain_core.language_models.fake_chat_models import FakeListChatModel
    from langchain_core.messages import AIMessage
    from langchain_core.outputs import ChatGeneration, ChatResult
    from langchain_core.language_models.chat_models import BaseChatModel
    class Fake(BaseChatModel):
        @property
        def _llm_type(self):
            return "fake"
        def _generate(self, messages, stop=None, run_manager=None, **kw):
            joined = "\n".join(str(m.content) for m in messages)
            return ChatResult(generations=[ChatGeneration(message=AIMessage(content=answer_for(joined)))])
        def bind_tools(self, tools, **kw):
            return self
    model = Fake()
else:
    from google.adk.models.base_llm import BaseLlm
    from google.adk.models.llm_response import LlmResponse
    from google.genai import types
    class Fake(BaseLlm):
        model: str = "fake"
        async def generate_content_async(self, llm_request, stream=False):
            joined = "\n".join(p.text for c in llm_request.contents for p in c.parts if p.text)
            yield LlmResponse(content=types.Content(role="model", parts=[types.Part(text=answer_for(joined))]))
    model = Fake()

outcome = "completed"
def go():
    global outcome
    try:
        spec = importlib.util.spec_from_file_location("app", os.path.join(folder, "app.py"))
        mod = importlib.util.module_from_spec(spec)
        sys.path.insert(0, folder)
        spec.loader.exec_module(mod)
        mod.build(model, send, ask).run("Where is my order 1234?")
    except BaseException as e:      # recorded, never hidden
        outcome = f"error: {type(e).__name__}: {str(e)[:120]}"
t = threading.Thread(target=go, daemon=True); t.start(); t.join(60)
if t.is_alive():
    outcome = "error: timed out after 60 s"
print(json.dumps({"sends": sends, "outcome": outcome}))
sys.stdout.flush()
os._exit(0)
