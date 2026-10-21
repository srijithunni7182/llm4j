#!/usr/bin/env python3
"""Records Jev's real replies for the request files in src/ai-agent4j/src/test/resources/jev/ (docs/specs/07-classifier, sub-phase 7c).

  JEV_API_KEY=... python3 scripts/record-jev-fixtures.py

For each NAME.request.json it writes NAME.response.json (the reply body, verbatim) and NAME.status (the HTTP status). It also records the 401 for a
deliberately wrong key. The key is read from the environment, sent only to api.typesafe.ai, and is never printed or written: no header is saved,
only the status and the body. Standard library only. A few cents of Jev usage per run.
"""
import json, os, sys, urllib.request, urllib.error, glob

URL = os.environ.get("JEV_BASE_URL", "https://api.typesafe.ai") + "/v1/systemone"
DIR = os.path.join(os.path.dirname(__file__), "..", "src", "ai-agent4j", "src", "test", "resources", "jev")


def post(body, key):
    req = urllib.request.Request(URL, json.dumps(body).encode(), {"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def save(name, status, text):
    with open(os.path.join(DIR, name + ".status"), "w") as f:
        f.write(str(status) + "\n")
    try:
        text = json.dumps(json.loads(text), indent=1, ensure_ascii=False)
    except ValueError:
        pass
    with open(os.path.join(DIR, name + ".response.json"), "w") as f:
        f.write(text + "\n")
    print(f"{name}: HTTP {status}")


def main():
    key = os.environ.get("JEV_API_KEY")
    if not key:
        sys.exit("JEV_API_KEY is not set")
    for path in sorted(glob.glob(os.path.join(DIR, "*.request.json"))):
        name = os.path.basename(path)[: -len(".request.json")]
        body = json.load(open(path))
        status, text = post(body, key)
        save(name, status, text)
    # a wrong key: the 401 shape
    status, text = post(json.load(open(os.path.join(DIR, "yesno.request.json"))), "not-a-real-key")
    save("unauthorized_401", status, text)


if __name__ == "__main__":
    main()
