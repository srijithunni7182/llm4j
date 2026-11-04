"""Fake models and a package search shared by probes P9 to P17. No network, no keys, no API money."""
import asyncio
import importlib.metadata as md
import pathlib
import re
import sys
import warnings

PACKAGES = ["langgraph", "langchain", "langchain-core", "google-adk"]


def versions():
    """The installed versions, printed at the top of every result so a claim names what it was run on."""
    out = []
    for p in PACKAGES:
        try:
            out.append(f"{p} {md.version(p)}")
        except md.PackageNotFoundError:
            out.append(f"{p} (not installed)")
    return "Versions: " + ", ".join(out) + f", Python {sys.version.split()[0]}"


def search(packages, pattern, limit=5):
    """Find `pattern` (a regex, case-insensitive) in the installed source of `packages`.

    Returns (number of files that match, up to `limit` relative paths). A probe reports
    "found no ..." only when this returns zero for a pattern chosen to find the feature.
    """
    rx = re.compile(pattern, re.I)
    hits = []
    for pkg in packages:
        try:
            dist = md.distribution(pkg)
        except md.PackageNotFoundError:
            continue
        roots = {pathlib.Path(dist.locate_file(f)).parents[0] for f in (dist.files or []) if str(f).endswith(".py")}
        seen = set()
        for f in dist.files or []:
            if not str(f).endswith(".py"):
                continue
            path = pathlib.Path(dist.locate_file(f))
            if path in seen or not path.exists():
                continue
            seen.add(path)
            try:
                if rx.search(path.read_text(errors="ignore")):
                    hits.append(f"{pkg}/{f}")
            except OSError:
                pass
    return len(hits), hits[:limit]


def report(rows):
    """Print `framework | question | finding` rows as a Markdown table."""
    print(versions())
    print()
    print("| Framework | Finding |")
    print("|---|---|")
    for fw, finding in rows:
        print(f"| {fw} | {finding} |")


class Quiet:
    """Collect every warning raised while building, so "nothing noticed before the run" is a measurement."""

    def __enter__(self):
        self._ctx = warnings.catch_warnings(record=True)
        self.caught = self._ctx.__enter__()
        warnings.simplefilter("always")
        return self

    def __exit__(self, *a):
        self._ctx.__exit__(*a)

    @property
    def messages(self):
        return [str(w.message) for w in self.caught if "deprecat" not in str(w.message).lower()]


# ---- LangChain / LangGraph fake chat model ---------------------------------------------------

def chat_model(replies, tool_calls=None):
    """A chat model that records every request it receives in `.seen` and plays `replies` in order."""
    from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
    from langchain_core.messages import AIMessage

    class Rec(GenericFakeChatModel):
        seen: list = []

        def _generate(self, messages, stop=None, run_manager=None, **kw):
            self.seen.append([str(m.content) for m in messages])
            return super()._generate(messages, stop=stop, run_manager=run_manager, **kw)

        def bind_tools(self, tools, **kw):
            return self

    items = []
    for i, r in enumerate(replies):
        if isinstance(r, AIMessage):
            items.append(r)
        else:
            items.append(AIMessage(content=r))
    return Rec(messages=iter(items), seen=[])


# ---- Google ADK fake model -------------------------------------------------------------------

def adk_model(replies):
    """An ADK model that records each request's contents in `.seen` and plays `replies` in order.

    A reply that is a `types.Part` (for example a function call) is returned as it is.
    """
    from google.adk.models.base_llm import BaseLlm
    from google.adk.models.llm_response import LlmResponse
    from google.genai import types

    class Rec(BaseLlm):
        model: str = "fake"
        seen: list = []
        calls: int = 0
        queue: list = []

        async def generate_content_async(self, llm_request, stream=False):
            self.seen.append(
                {
                    "contents": [(c.role, [p.text for p in c.parts if p.text]) for c in llm_request.contents],
                    "system": str(llm_request.config.system_instruction) if llm_request.config else "",
                    "tools": sorted((llm_request.tools_dict or {}).keys()),
                }
            )
            item = self.queue[min(self.calls, len(self.queue) - 1)]
            self.calls += 1
            part = item if isinstance(item, types.Part) else types.Part(text=item)
            yield LlmResponse(content=types.Content(role="model", parts=[part]))

    return Rec(seen=[], queue=list(replies))


def adk_run(agent, text, state=None, app="probe", user="u", runner=None, session=None):
    """Run an ADK agent once; returns (runner, session_id, final text)."""
    from google.adk.runners import InMemoryRunner
    from google.genai import types

    async def go():
        r = runner or InMemoryRunner(agent=agent, app_name=app)
        s = session or await r.session_service.create_session(app_name=app, user_id=user, state=state or {})
        final = ""
        async for ev in r.run_async(user_id=user, session_id=s.id, new_message=types.Content(role="user", parts=[types.Part(text=text)])):
            if ev.content and ev.content.parts:
                for p in ev.content.parts:
                    if p.text:
                        final = p.text
        return r, s.id, final

    return asyncio.run(go())
