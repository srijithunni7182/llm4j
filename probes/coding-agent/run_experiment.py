"""Probe P18: does the feedback of a checker change what a coding agent ships?

usage: PROBE_PYTHON=venv/bin/python OPENAI_API_KEY=... python3 probes/coding-agent/run_experiment.py --trials 5
Runs each arm for each trial (the agent has file tools and a short allow-list of commands, no shell) (interleaved, so a spend cap cannot favour an arm), scores every finished artifact and
writes results/sessions/*.json. Needs the Loom module built (target/classes and target/lib). Real model calls: gpt-4o-mini.
"""
import argparse, json, os, pathlib, re, shutil, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import agent_loop, tasks

REPO = HERE.parents[1]
MOD = REPO / "src/loom/ai-agent4j-loom"
CP = f"{MOD}/target/classes:{MOD}/target/lib/*"
PY = os.environ.get("PROBE_PYTHON", "python3")
SCENARIOS = ["S0", "S1", "S2", "S3"]


LOOM_COMMANDS = ["weave check", "weave review", "weave explain", "weave graph", "weave man"]
PYTHON_COMMANDS = ["python"]
SAFE_ARGUMENT = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_./-]*$")


def environment(session):
    """Minimal: no API key of ours, and the fake values a Loom check needs to see its variables set."""
    return {"PATH": "/usr/bin:/bin", "HOME": str(session), "LANG": "C.UTF-8", "GEMINI_API_KEY": "test-key", "OPENAI_API_KEY": "test-key",
            "ANTHROPIC_API_KEY": "test-key", "SLACK_WEBHOOK": "https://example.com/hook", "JAVA_TOOL_OPTIONS": ""}


def no_network():
    """A prefix that starts a program without network access, where the container allows it."""
    probe = subprocess.run(["unshare", "-rn", "true"], capture_output=True)
    return ["unshare", "-rn"] if probe.returncode == 0 else []


def make_runner(arm, work, env):
    """Runs one allowed command without a shell; anything else is refused."""
    prefix = no_network() if arm != "loom" else []
    java = shutil.which("java")

    def run(command, argument):
        allowed = LOOM_COMMANDS if arm == "loom" else PYTHON_COMMANDS
        if command not in allowed:
            return f"not allowed: {command}", 126
        if not SAFE_ARGUMENT.match(argument) or ".." in argument:
            return "the argument must be a plain file name or topic in the working folder", 126
        if arm == "loom":
            argv = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", CP, "io.github.llm4j.loom.cli.WeaveCLI"] + command.split()[1:] + [argument]
        else:
            argv = prefix + [PY, argument]
        try:
            p = subprocess.run(argv, cwd=work, env=env, capture_output=True, text=True, timeout=60)
            text = p.stdout + p.stderr
            text = "\n".join(l for l in text.splitlines() if not re.match(r"Picked up|INFO|loadRecursive|WARNING|[A-Z][a-z][a-z] [0-9][0-9], ", l))
            return text, p.returncode
        except subprocess.TimeoutExpired:
            return "timed out after 60 s", 124

    return run


def score(arm, work, classes):
    out = {}
    for s in SCENARIOS:
        try:
            if arm == "loom":
                p = subprocess.run(["java", "-cp", f"{CP}:{classes}", "Score", str(work), s], capture_output=True, text=True, timeout=120)
            else:
                p = subprocess.run([PY, str(HERE / "score_python.py"), str(work), "adk" if arm == "adk" else "langgraph", s],
                                   capture_output=True, text=True, timeout=120)
            line = [l for l in p.stdout.splitlines() if l.startswith("{")]
            out[s] = json.loads(line[-1]) if line else {"sends": [], "outcome": "error: no result: " + (p.stderr or p.stdout)[-160:]}
        except subprocess.TimeoutExpired:
            out[s] = {"sends": [], "outcome": "error: scorer timed out"}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arms", default="loom,langgraph,adk,langgraph_informed")
    ap.add_argument("--trials", type=int, default=5)
    ap.add_argument("--first-trial", type=int, default=1)
    ap.add_argument("--out", default=str(HERE / "results"))
    ap.add_argument("--max-tokens", type=int, default=4_000_000)
    ap.add_argument("--max-dollars", type=float, default=2.0)
    args = ap.parse_args()
    out = pathlib.Path(args.out); (out / "sessions").mkdir(parents=True, exist_ok=True)
    spend = agent_loop.Spend(out / "spend.json", args.max_tokens, args.max_dollars)
    classes = out / "score-classes"; classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "-cp", CP, "-d", str(classes), str(HERE / "loom/Score.java")], capture_output=True, check=True)
    for trial in range(args.first_trial, args.first_trial + args.trials):
        for arm in args.arms.split(","):
            result_file = out / "sessions" / f"{arm}-{trial}.json"
            if result_file.exists():
                continue
            try:
                spend.check()
            except agent_loop.SpendCap as e:
                print("STOP:", e); return
            session = pathlib.Path(os.environ.get("PROBE_WORK", "/tmp/probe-coding-agent")) / f"{arm}-{trial}"
            if session.exists():
                shutil.rmtree(session)
            work = session / "work"; work.mkdir(parents=True)
            env = environment(session)
            before = (spend.tokens_in, spend.tokens_out)
            commands = LOOM_COMMANDS if arm == "loom" else PYTHON_COMMANDS
            run = agent_loop.run_session(tasks.ARMS[arm]["system"], tasks.ARMS[arm]["task"], work, make_runner(arm, work, env), commands, spend)
            scores = score(arm, work, classes)
            record = {"arm": arm, "trial": trial, "tokens_in": spend.tokens_in - before[0], "tokens_out": spend.tokens_out - before[1],
                      "dollars": round((spend.tokens_in - before[0]) * agent_loop.PRICE_IN + (spend.tokens_out - before[1]) * agent_loop.PRICE_OUT, 4),
                      "run": run, "scores": scores,
                      "files": sorted(str(p.relative_to(work)) for p in work.rglob("*") if p.is_file())}
            result_file.write_text(json.dumps(record, indent=1))
            # keep the artifact itself with the record: it is what was scored
            keep = out / "artifacts" / f"{arm}-{trial}"
            if keep.exists():
                shutil.rmtree(keep)
            shutil.copytree(work, keep, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "eval-runs"))
            s0 = scores["S0"]
            print(f"{arm}-{trial}: turns {run['turns']} finished {run['finished']} error {run['error']} | S0 sends {len(s0['sends'])} | total ${spend.dollars:.3f}")
    print("done; spend", spend.tokens_in + spend.tokens_out, "tokens", f"${spend.dollars:.3f}")


if __name__ == "__main__":
    main()
