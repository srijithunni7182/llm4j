# ⚡ GetViral — one idea, every feed

**GetViral** is a multi-agent creator studio. Type one idea and a team of twelve AI agents turns it into a
ready-to-post pack for **X**, **Instagram Reels** and **YouTube**. The idea is researched on the open web with
cited sources and timed against what's trending *right now*. The pack is then illustrated, cut into a video,
scored by a critic, graded by LLM judges and, with your approval, published to Instagram.

It runs as a multi-user website (sign-in, onboarding, connected accounts, a library of everything you've made)
that deploys to Google Cloud Run, and it still runs on your laptop with one command.

It doesn't stop at text: an **ArtDirector** agent generates the thumbnail, Reel cover, B-roll frames and X card,
and a **VideoEditor** agent renders the Reel into a real vertical **MP4**.

It is the showcase for the whole llm4j stack: every module does real work in it.

![GetViral composer](docs/composer.png)

| The room, live | You pick the hook | The pack |
|---|---|---|
| ![Studio](docs/studio-live.png) | ![Hook picker](docs/hook-picker.png) | ![Results](docs/results.png) |

![Generated visuals: YouTube thumbnail, X card, Reel cover, B-roll and the rendered Reel](docs/visuals.png)

---

## Launch it (Ubuntu / Linux / macOS)

```bash
cd examples/getviral
./launch.sh                    # asks: Gemini (hidden key prompt), Ollama (local) or Demo (no key)
./launch.sh --install-desktop  # optional: adds "GetViral Studio" to the Ubuntu app grid
```

The launcher:
- checks Java 17+ and Maven (and tells you the `apt install` line if either is missing);
- asks which model to use, verifies a Gemini key live with Google, and can save it to
  `~/.getviral/credentials` (mode `600`, remove with `--forget-keys`);
- lists your pulled Ollama models, and offers optional Instagram publishing and Veo video;
- builds the stack from source and opens the studio in your browser.

Locally you sign in with any email (a dev-only login, since Google sign-in isn't configured), and everything
is stored in `./getviral-data`: an embedded H2 database, generated media and memory.

Keys only ever reach Java through the environment, never the command line. Other flags: `--demo`,
`--gemini`, `--ollama`, `--cli "idea"`, `--port N`, `--skip-build`.

## Or run it by hand (no API key)

```bash
# from the repo root — builds ai-agent4j, addons, Loom, Engram, eval4j and GetViral from source
mvn -q install -DskipTests -pl examples/getviral -am
cd examples/getviral
GETVIRAL_DEV_LOGIN=true mvn -q exec:java   # → http://localhost:7070
```

With no key, GetViral runs its scripted **demo studio model**, which still drives the real workflow,
calls the real public APIs, uses real RAG and Engram memory, and runs the real eval4j judges.

```bash
export GEMINI_API_KEY=...              # real writing + real LLM-as-judge
GETVIRAL_MODE=ollama GETVIRAL_MODEL=ollama/gemma3 mvn -q exec:java   # fully local
mvn -q exec:java -Dexec.args='--cli "why walking meetings beat Zoom calls"'  # terminal mode
```

---

## What happens when you hit "Make it viral"

```mermaid
flowchart LR
    B[Brief] --> G{{PII guardrail}}
    G -- personal data --> S[SafetyCoach]
    G --> SR[🎬 Showrunner<br/>writes every prompt]
    SR --> RS[🔬 Researcher<br/>web search · reads sources] & TS[📡 TrendScout<br/>live trend signals]
    RS & TS --> ST[🧭 Strategist<br/>angle · 5 hooks · sourced facts]
    ST --> H([👆 You pick the hook])
    H --> X[𝕏 XWriter] & R[🎞️ ReelDirector] & Y[▶ YouTubeProducer]
    X & R & Y --> C[🔥 ViralityCritic]
    C -- REVISE --> SR2[🎬 Showrunner re-casts prompts] --> X & R & Y
    C -- SHIP --> Q[[eval4j quality gate]]
    C -- SHIP --> AD[🎨 ArtDirector<br/>thumbnail · cover · B-roll · X card] --> VE[📹 VideoEditor<br/>renders the Reel MP4]
    VE --> P([👆 Publish?]) --> PB[🚀 Publisher] --> A([✅ You approve]) --> IG[(Instagram API)]
```

1. **Guardrail** — Loom's `guardrail (PII)` blocks briefs containing personal data before any agent runs.
2. **Casting** — the **Showrunner** (orchestrator agent) reads the brief, Engram's memory of the creator and
   the viral playbook (RAG), then **writes a bespoke system prompt for every specialist**, as a typed
   `output_schema` casting sheet.
3. **Research**: two agents work in a Loom `parallel` block.
   - The **Researcher** researches the idea itself on the open web. It runs `web_search`, reads the two or
     three best sources in full with `read_page`, and returns a typed dossier: a summary, findings that each
     carry their URL, fresh angles, what people debate, and caveats for anything it couldn't verify.
   - The **TrendScout** pulls live trend signals from keyless public APIs, to time the post.
4. **Strategy** — **Strategist** returns a typed plan: angle, five hooks, key facts taken from the research
   (with their sources), hashtags and timing. The YouTube description ends with a Sources list, and the
   critic's trust score checks claims against the dossier.
5. **You pick the hook** — Loom `human_prompt`.
6. **Create** — three platform specialists run in a Loom `parallel` block.
7. **Critique loop** — **ViralityCritic** scores the pack; on `REVISE` the Showrunner **rewrites the prompts**
   of the agents that fell short, and they try again. `loop until (criticReport.verdict == "SHIP")`, with a
   symbolic revision budget the critic can't talk its way past.
8. **Quality gate** — eval4j LLM-as-judge conditions grade the pack (hook strength, platform fit, honest
   packaging, groundedness, toxicity) and the studio shows them as badges.
9. **Visuals** — the **ArtDirector** generates a 16:9 YouTube thumbnail, a 9:16 Reel cover, two 9:16 B-roll
   frames and a 16:9 X card in one consistent style (the Showrunner writes its art direction). The
   **VideoEditor** then renders the Reel's beat sheet into a vertical MP4 over those images, and can add an
   AI video clip from Google Veo if you opt in.
10. **Publish** — optional, behind *two* human gates: you supply the video URL, and the `instagram_publish`
   tool declares `requiresApproval()`, so ai-agent4j pauses for your explicit OK.

Rate any platform 🔥/👎 and it becomes an Engram memory: the next run is briefed with it.

---

## How each module is used

| Module | What it does in GetViral | Where |
|---|---|---|
| **Loom** | The whole workflow is a `.loom` script: guardrail, `parallel`, `loop until`, `alt`, `human_prompt`, typed `output_schema`, `retry`/`on_failure`, `observe`, audit log. `HarnessExecutor` hooks stream events, inject prompts and budget the critic. | [`getviral.loom`](src/main/resources/getviral/getviral.loom), [`GetViralExecutor`](src/main/java/io/github/llm4j/getviral/engine/GetViralExecutor.java) |
| **ai-agent4j** | ReAct agents, tools, `AgentEventListener` for the live wire, Human-in-the-Loop `ApprovalCallback`, Gemini/Ollama providers, audit logging. | [`tools/`](src/main/java/io/github/llm4j/getviral/tools) |
| **ai-agent4j-addons** | Local RAG: `OnnxEmbeddingProvider` (or bundled MiniLM) + in-memory or `PGVectorStore` over the viral playbook and each creator's past posts. | [`KnowledgeBase`](src/main/java/io/github/llm4j/getviral/rag/KnowledgeBase.java) |
| **Engram** | Per-creator long-term memory as Loom's `MemoryEngine`: briefs the Showrunner and Strategist, learns from the Strategist and Critic, stores hook choices and 🔥/👎 feedback; newer feedback *shadows* older. | [`CreatorMemory`](src/main/java/io/github/llm4j/getviral/engine/CreatorMemory.java) |
| **eval4j** | **At runtime** — `LlmJudgeCondition.evaluate()` powers the quality gate. **In tests** — trajectory assertions on each agent, YAML golden briefs with a `PassRate` bar, safety, memory and publishing evals. | [`QualityGate`](src/main/java/io/github/llm4j/getviral/quality/QualityGate.java), [`src/test`](src/test/java/io/github/llm4j/getviral) |

### Dynamic prompts: fixed frame, dynamic body

No specialist runs on a hand-written prompt. The Showrunner writes them per brief and rewrites them per
revision round, and `HarnessExecutor#agentForDelegate` swaps them in just before each agent's turn
(`agent.toBuilder().instructions(prompt)`, so each agent keeps its tools, listeners and approval gate).
Every generated prompt is wrapped in a fixed identity line and **house rules** (no invented stats, no
personal data, platform limits, exact format), so creative orchestration can never prompt away the
safety rules. Open any agent in the studio to see its prompt history (v1 → v2).

### Tools on free public REST APIs

| Tool | API | Used for |
|---|---|---|
| `web_search` | **Google Search** via Gemini search grounding (when a Gemini key is set) + **GDELT** news, last 30 days + Wikipedia full-text search + DuckDuckGo instant answers | researching the idea itself; numbered sources with URLs |
| `read_page` | any public web page | reading a source in full before citing it (title, date, main text) |
| `trending_now` | Wikimedia REST — most-read articles | what the internet is curious about today |
| `hn_pulse` | Hacker News via Algolia search | live debates → contrarian angles |
| `trending_hashtags` | Mastodon `trends/tags` | hashtags trending right now |
| `moment_calendar` | Nager.Date public holidays | timing posts to cultural moments |
| `fact_check` | Wikipedia search + page summary | grounding claims before they go viral |
| `word_lab` | Datamuse | punchier vocabulary, rhymes, associations |
| `trending_audio` | Apple Music RSS "most played" | picking a Reel soundtrack |
| `broll_finder` | Openverse | openly-licensed B-roll & thumbnail references |
| `viral_playbook` | local RAG (addons) | hook formulas, platform tactics, creator voice |
| `generate_image` | Gemini image generation → **Pollinations.ai** (free, keyless) → local Java2D render | thumbnail, Reel cover, B-roll, X card |
| `render_reel` | local — Java2D frames + jcodec H.264 | the Reel as a real 9:16 MP4 |
| `generate_video_clip` | Google Veo via the Gemini API (opt-in, paid) | AI B-roll clips |
| `instagram_quota` / `instagram_publish` | Instagram Platform Content Publishing API | `POST /{ig-user-id}/media` → poll `status_code` → `POST /{ig-user-id}/media_publish` |

Everything except Google Search is keyless, so research still works on Ollama or with no key. With a Gemini
key, Google Search grounding runs on the studio model (or `GETVIRAL_SEARCH_MODEL`); Google bills grounded
requests beyond the free tier.

`read_page` runs on a shared server, so it only fetches public pages. It allows http(s) on standard ports
only, and it refuses loopback, private, link-local (including cloud metadata) and other internal addresses,
checked again on every redirect. Page size is capped. Text from the web is treated as material, not
instructions: a house rule tells every agent never to follow instructions found in pages or tool output.

Each public-API tool tries the live endpoint first; if a host is unreachable it falls back to a recorded
sample **and says so** in the observation and the UI (● live / ○ sample), so no agent mistakes a sample for
live data. Set `GETVIRAL_OFFLINE_APIS=true` to use samples only.

### Images and video

- **Images** try each provider in turn: **Gemini** image generation when `GEMINI_API_KEY` is set, then the
  free keyless **Pollinations.ai** API, then a local Java2D design render so the pipeline still works offline.
  Local renders are labelled *"local design render — not AI"* everywhere; they are placeholders, not photos.
- **Words are never left to the image model.** Prompts describe the scene only, and GetViral typesets the
  overlay text (thumbnail and cover titles) itself, because image models are unreliable at legible text.
  Every image also keeps a text-free plate, and the Reel is rendered over those.
- **The Reel MP4** is rendered frame by frame in Java2D and encoded to H.264 with jcodec, so no ffmpeg is
  needed. It has a slow zoom-and-pan on each image, a flash cut on every beat, a pop-in headline, subtitles,
  story progress bars and your handle. It is silent: the beat sheet names the soundtrack to add in your
  editor or in Instagram.
- **AI video** (`GETVIRAL_VEO=true`) calls Google Veo's long-running generation API. It is paid and slow, so
  it is off by default.

### Niches

The niche is free text, with one-tap chips for fitness, productivity, food, tech, travel, money, beauty,
education, **culture, books, cinema, comedy** and **sports**. The RAG playbook has a
[niche guide](src/main/resources/getviral/playbook/niches.md) the Showrunner and Strategist retrieve.
Examples: no spoilers in cinema hooks, no broadcast footage in sports, punch up in comedy, and no long
quotes in books.

### Instagram publishing

On the website, each creator connects **their own** Instagram account during onboarding, and a run only
ever publishes there. For terminal runs, set `IG_USER_ID` and `IG_ACCESS_TOKEN` for an Instagram professional account with the content-publish
permission (optionally `IG_GRAPH_HOST`, default `https://graph.instagram.com`, and `IG_GRAPH_VERSION`,
default `v23.0`). Without them, publishing is an honest **dry run** that shows the exact Graph API calls it
would make. Instagram must be able to download the video from a public `https://` URL. The tool validates
caption length (2,200), hashtags (30) and mentions (20) before calling the API.

---

## The website (hosted mode)

GetViral is a Spring Boot app built for many creators at once:

- **Sign in with Google.** Sessions are stored in the database (Spring Session JDBC), so any instance can
  serve any user.
- **Onboarding** has four steps:
  1. your profile (handle, niche, tone, audience);
  2. connect Instagram, YouTube and X (OAuth; tokens are encrypted with AES-GCM at rest);
  3. teach it your voice with a few past posts;
  4. a tour of how the studio works, ending in a guided first pack.
- **Runs** are queued on a bounded worker pool, with one active run per creator and a monthly pack quota on
  the platform key. Every event and question is written to the database, and the live view is an SSE stream
  that tails it (resumable with `Last-Event-ID`).
- **The Library** holds every pack and every generated image and video, with Markdown export. Creators can
  delete their account and all of their data.

### Run it like production, on your laptop

```bash
cd examples/getviral
docker compose up --build      # the production image + PostgreSQL 16 → http://localhost:7070
```

This uses the same container that Cloud Run runs and the same database engine as Cloud SQL, with the local
dev login. `mvn test` also re-runs the whole hosted end-to-end suite on a real PostgreSQL through
Testcontainers whenever Docker is available.

### Deploy to Google Cloud Run

See **[DEPLOY.md](DEPLOY.md)** for the one-time setup (Cloud SQL, a Cloud Storage bucket, Secret Manager,
OAuth apps) and `gcloud builds submit --config examples/getviral/cloudbuild.yaml .` for every release after
that. On Cloud Run, the app refuses to start with laptop settings: dev login, no shared token key, H2, or no
media bucket.

---

## Evals

```bash
mvn test                              # 29 tests on the demo model — deterministic, no network needed
GEMINI_API_KEY=... GETVIRAL_OFFLINE_APIS=false mvn test   # same suite against a real model + live APIs
```

| Suite | Checks |
|---|---|
| `GetViralWorkflowEvalTest` | Full workflow ships all three platforms; TrendScout's tool trajectory; Strategist uses RAG and returns valid JSON; the hook flows into every platform; prompts are written at runtime and re-cast after feedback; the critic loop is bounded; five eval4j badges; Engram remembers. |
| `GoldenBriefsEvalTest` | YAML golden briefs through `@ParameterizedTest`, aggregated with `PassRate.requireAtLeast(0.9)`. |
| `SafetyAndPublishingEvalTest` | The PII guardrail blocks before any content is written; approved publish → dry run; rejected publish never reaches Instagram. |
| `VisualsEvalTest` | The ArtDirector generates all five visuals with the right aspect ratios; the VideoEditor renders a valid MP4 (`ftyp` box, plausible duration); offline images are labelled as not AI. |
| `CreatorMemoryEvalTest` | The second run is briefed with what the first learned; newer feedback shadows older. |

---

## Configuration

| Variable | Default | |
|---|---|---|
| `GETVIRAL_MODE` | `auto` | `gemini`, `ollama`, `demo` (`auto` = Gemini if `GEMINI_API_KEY` is set) |
| `GETVIRAL_MODEL` / `GETVIRAL_JUDGE_MODEL` | `gemini-3.5-flash` / same | any model `DefaultLLMClientFactory` understands |
| `GETVIRAL_PORT` / `PORT` | `7070` | studio port (`PORT` is set by Cloud Run) |
| `GETVIRAL_SEARCH_MODEL` | studio model, else `gemini-2.5-flash` | Gemini model for Google Search grounding |
| `GETVIRAL_DATA_DIR` | `./getviral-data` | Engram memories, voice samples, audit logs |
| `GETVIRAL_MAX_REVISIONS` | `2` | critic rounds before shipping anyway |
| `GETVIRAL_OFFLINE_APIS` | `false` | use recorded API samples only |
| `GETVIRAL_ONNX_MODEL` / `GETVIRAL_ONNX_TOKENIZER` | – | local ONNX embeddings via addons |
| `GETVIRAL_PGVECTOR_URL` (+ `_USER`, `_PASSWORD`) | – | persist RAG vectors in PostgreSQL pgvector |
| `GETVIRAL_IMAGE_PROVIDER` | `auto` | `gemini`, `pollinations` or `local` |
| `GETVIRAL_IMAGE_MODEL` | `gemini-2.5-flash-image` | Gemini image model |
| `GETVIRAL_VEO` / `GETVIRAL_VEO_MODEL` | `false` / `veo-3.0-fast-generate-001` | opt-in AI video clips (paid) |
| `GETVIRAL_REEL_SIZE` | `540x960` | rendered Reel resolution (e.g. `1080x1920`) |
| `IG_USER_ID` / `IG_ACCESS_TOKEN` | – | Instagram publishing for terminal runs (the website uses each creator's connected account) |

Website settings:

| Variable | Default | |
|---|---|---|
| `GETVIRAL_DB_URL` (+ `_USER`, `_PASSWORD`) | embedded H2 file | PostgreSQL / Cloud SQL JDBC URL |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | – | Google sign-in |
| `GETVIRAL_DEV_LOGIN` | `false` | sign in with any email — **local only**, refused on Cloud Run |
| `GETVIRAL_PUBLIC_URL` | `http://localhost:7070` | base of OAuth redirect URIs |
| `GETVIRAL_TOKEN_KEY` | generated locally | base64 AES-256 key for social tokens (required on Cloud Run) |
| `GETVIRAL_GCS_BUCKET` | – | store media in Cloud Storage instead of local disk |
| `GETVIRAL_PACKS_PER_MONTH` / `GETVIRAL_WORKERS` | `20` / `8` | quota per creator / concurrent runs per instance |
| `GETVIRAL_SECURE_COOKIES` | `false` | `true` behind HTTPS |
| `INSTAGRAM_APP_ID` / `_SECRET`, `YOUTUBE_CLIENT_ID` / `_SECRET`, `X_CLIENT_ID` / `_SECRET` | – | account connections (YouTube falls back to the Google client) |

## Project layout

```
src/main/resources/getviral/
  getviral.loom          the workflow + agent team (Loom DSL)
  playbook/*.md          RAG knowledge: hooks, X, Reels, YouTube, retention, trust & safety, niches
  fixtures/*.json        recorded public-API samples (offline fallback)
  web/                   landing, onboarding, studio + library (vanilla HTML/CSS/JS over SSE)
  db/migration/          Flyway schema (users, runs, events, connections, memory, sessions)
src/main/java/io/github/llm4j/getviral/
  engine/                GetViralEngine, GetViralExecutor (Loom hooks), PromptBook, CreatorMemory
  app/                   the Spring Boot website: security, accounts, runs, media, memory sync, connections
  tools/                 web research, public REST API tools, image/video tools, Instagram publishing
  media/                 image providers, PosterArt design kit, ReelRenderer (MP4), Veo client
  rag/                   KnowledgeBase (addons embeddings + vector store)
  quality/               QualityGate (eval4j at runtime)
  llm/                   model routing, metering, the demo studio model
  web/                   terminal studio, Markdown export
Dockerfile, docker-compose.yml, cloudbuild.yaml, DEPLOY.md   container + Cloud Run
```
