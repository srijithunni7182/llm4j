# {{name}}

A small web page in front of a Loom workflow. You type a topic, an agent writes an article, **you approve it in the page**, and only then is it saved to `output/`.

## What is where

| File | What it is |
|------|------------|
| `src/main/resources/main.loom` | The workflow: write, ask a person, save. It decides everything. |
| `src/main/resources/prompts/writer.md` | What the writer is told (at least 500 words). |
| `src/main/java/web/App.java` | The host: a small web server and three calls (`/api/run`, `/api/state`, `/api/answer`). It shows the run and decides nothing. |
| `src/main/java/web/Session.java` | One run on its own thread. It forwards the question to the page and waits for the answer. |
| `src/main/java/web/SaveMarkdown.java` | The save step, as code: it runs only when the script says so. |
| `src/main/resources/web/index.html` | The page. Plain HTML and a little JavaScript, no build step. |
| `src/test/resources/eval/golden` | Example topics and what a good article is (at least 500 words: counted by code, not judged). |
| `src/test/java/web/WebHostTest.java` | Runs the host on a model that costs nothing, including "nothing is saved before a person says yes". |

## Try it for free

```
mvn test            # the tests; they cost nothing
sh run.sh --mock    # the page at http://localhost:8080, with a model that costs nothing
```

The mock writes `[mock answer]`, not an article: it shows that the page, the approval and the save work.

## Run it for real (this spends money)

1. Set a spending limit in your model provider's dashboard first.
2. `cp .env.example .env` and put your key after `GEMINI_API_KEY=`.
3. `sh run.sh`. The page shows what each run has cost; the script's `budget` stops a run at 60000 tokens.

Check the workflow without the page: `mvn compile` first (so `weave` can see `SaveMarkdown`), then `weave check src/main/resources/main.loom` and `weave eval src/main/resources/main.loom --mock`.
