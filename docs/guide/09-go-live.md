# 9. Go live: real APIs, budgets and cost checks

**Goal:** run the workflow on real models in stages, with limits at three levels, and compare the bill with your model.

## Three nets, from outside in

1. **Provider limit** (their dashboard): the one thing no bug in your code can bypass. Set it before anything else, below what you could afford to lose.
2. **Your cap** (`SpendGuard`, or Loom's `--max-cost`): counts real tokens and stops the whole run. Set it below the provider limit.
3. **Per-step budgets** (Loom `budget`, per agent, per call, per loop): stop one runaway step, not the whole run.

```loom
budget { tokens: 200000  calls: 150  warn_at: 80% }                          // whole run
agent Writer { model: "gemini/gemini-2.5-flash"  budget { tokens: 20000  per_call: 2000 } }
delegate "Draft {topic}" to Writer -> draft budget 5000 tokens on_failure { note "Out of budget: {_error}" }
loop until (review.verdict == "OK") max 5 budget 30000 tokens { ... } on_exhausted { note "Stopped after {_loopRounds} rounds" }
rate_limits { on_limit: suspend  max_wait: 24h  max_resumes: 50 }
```

`when_exhausted` is `stop` (default), `suspend` (a durable run pauses and `weave resume` continues) or `ask`.
Costs need a price table: `prices.properties` with lines like `gemini/gemini-2.5-flash = 0.30 / 2.50` (per million tokens, input / output).

## Run it

```
weave check hexamind.loom && weave audit hexamind.loom --fail-on medium     # free, first, always
weave run hexamind.loom --max-cost 0.50 --prices prices.properties --journal runs/debate-1
weave run hexamind.loom --max-tokens 50000 --max-calls 40 --trace            # trace events to stderr
weave resume runs/debate-1                                                   # continue a paused or failed run
```

A spend table (calls, tokens, cost per agent) prints at the end of a budgeted run. Exit codes: 0 done, 1 failed, 2 bad options, 3 budget, 4 paused, 5 `--stop-at`.
A **journal** makes the run durable: re-running with the same journal replays finished steps for free, and `weave timeline <dir>` lists every step and its cost.

## Roll out in stages

1. **`check` and `audit`** (free).
2. **Smoke:** one tiny real run (one case, a small `--max-cost`). Confirm keys, tools, trace and spend report; compare *measured* tokens per call with your model.
3. **One real run of the cheapest representative case**, then the next. Stop if the first costs more than about twice the estimate: your model is wrong, so fix it before spending more.
4. **Everything else.** Keep the journal and the replay/judge caches so a failure does not re-pay for what finished.

## Check the cost afterwards

There is no `weave estimate`; keep a small cost model (calls x tokens x price) and compare. Hexamind's is [`cost_model.py`](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/cost_model.py): expected a full debate at
about 121 model calls, measured against the spend report. When reality and model disagree, change the model, not just the cap.

Rate limits: a 429 with a short reset is waited out inside the call; a long one reaches Loom's `rate_limits`. A free-tier key will hit them: pace the calls (Hexamind's evaluation used
about 10 a minute) rather than hammering.

## An application with its own interface

Loom runs the workflow; **the interface, the transcript view and the agents' surroundings are your application's.** (An agent following the skill
writes that code in your project, in whatever UI you choose.) The host needs three things from the executor, and all three are there:

```java
HarnessExecutor executor = new HarnessExecutor(new LoomLoader().load("workflow.loom"), tools, clients);
executor.addTraceListener(event -> ui.append(event));      // 1. the transcript (add it BEFORE initialize())
executor.setHumanInterface(ui::askPerson);                 // 2. questions and approvals go to your screen
executor.setJournal(journal);                              //    optional: lets a question wait for days (below)
executor.initialize();
executor.executeWorkflow("Main", Map.of("topic", topic));
Object post = executor.getContext().getAll().get("post_text");   // 3. what the workflow made
executor.shutdown();
```

1. **Transcript.** A `TraceEvent` has `type`, `agent`, `step`, `text`, `data` and `at`. The types a screen wants: `delegate_start` and `delegate_end`
   (an agent began, and its answer), `thought`, `action` and `observation` (what an agent is thinking and which tool it calls), `note` (a message
   the workflow wants shown), `approval`, `budget`, `guard`, `checkpoint`, `rewind`, `suspended`. Listeners run on the thread that runs the
   workflow, so hand the event to your UI thread and return quickly; an exception in a listener is logged and does not stop the run.
2. **Questions.** `human_prompt`, a tool that needs approval and a `decide` that asks all arrive at your `HumanInterface`. Override
   `promptHuman(stepId, message, hints)` to learn what kind of question it is (`PROMPT`, `APPROVAL`, `DECIDE`), the choices to offer, and who it is for.
   Return the answer to continue now. Or throw `RunSuspended(stepId, message)` to **pause without holding a thread**: show the question, and when the person
   answers, call `journal.answer(stepId, answer)` and run `executeWorkflow` again with the same journal; the steps already done are replayed (the transcript
   shows `delegate_replayed`, not a second model call) and the run goes on from the question.
3. **Result.** `executor.getContext().getAll()` holds every variable the workflow set (`-> name`). Read the ones you need by name; an unset variable is
   absent from the map.

Keep the view separate from the workflow: the screen should not decide anything the script decides, and the script should not know there is a screen.
The agent writes tests for the host and its screen too, in the application's own stack and run by its own build, not with `weave`. Run the workflow itself free first (`weave eval --mock`) before any real run.

## Keys and secrets: two different people

**A developer running the workflow and its evaluations on their own machine** (including an LLM as judge). Use a `.env` file beside the script:

```bash
cp .env.example .env        # then put your key after GEMINI_API_KEY=
```

`weave init` writes `.env.example` and a `.gitignore` that ignores `.env`. `weave run`, `weave check`, `weave eval` and `weave next` read the file, say which names they
found (never the values), and let a variable already set in your shell win. `weave` refuses a `.env` that git tracks (its keys are already in the history: remove it with
`git rm --cached .env` and rotate them), warns when it is not ignored or other users can read it, and `--env-file <file>` / `--no-env-file` choose another file or none.
A judge model from another provider (`weave eval --judge ...`) needs that provider's key in the same file. If a key is ever pasted somewhere shared, rotate it.

**Someone deploying the application** (a server, shared or production keys). Never the project's `.env` there. Use the encrypted secret store, a one-time setup:

```bash
weave secrets create --secrets /etc/myapp/keys.store
weave secrets set GEMINI_API_KEY --secrets /etc/myapp/keys.store
weave run workflow.loom --secrets /etc/myapp/keys.store --secrets-key-env MYAPP_MASTER_KEY
```

A tool's key is `secret.NAME` in the script; you choose and protect the store's path and master key. Environment variables (`env.NAME`) also work. The store is described in the
[secret store page](https://github.com/srijithunni7182/llm4j/blob/main/ai-agent4j/wiki/Secret-Store.md). Secrets are scrubbed from results, traces, journals and audit logs. When the keys must
come from a cloud vault instead, see the next section.

### Keys in Google Secret Manager (or another vault)

`weave` itself reads keys from the encrypted file store or the environment; it does not call a cloud vault. When keys must come from Google
Secret Manager, AWS Secrets Manager, HashiCorp Vault or the like, the way to do it is a **small Java host that you (or an agent following the
skill) write**: it implements `SecretStore` over the vault's client and hands it to the executor. The script does not change: models still find
their usual key name, and a tool's key is still `api_key: secret.NAME`.

```java
/** Reads each secret from Google Secret Manager when it is asked for, so a rotated key is picked up without a restart. */
final class GoogleSecretStore implements SecretStore {
    private final SecretManagerServiceClient client;
    private final String project;

    GoogleSecretStore(SecretManagerServiceClient client, String project) { this.client = client; this.project = project; }

    @Override public String resolve(String name) {
        try {
            return client.accessSecretVersion(SecretVersionName.of(project, name, "latest")).getPayload().getData().toStringUtf8();
        } catch (NotFoundException e) {
            throw new SecretNotFoundException(name);
        }
    }
    @Override public boolean contains(String name) { try { resolve(name); return true; } catch (SecretNotFoundException e) { return false; } }
    @Override public Set<String> names() { return Set.of(); }   // listing is not needed to resolve
    @Override public Optional<SecretMetadata> metadata(String name) { return Optional.of(SecretMetadata.NONE); } // or allowing("api.example.com")
}

SecretStore secrets = new GoogleSecretStore(SecretManagerServiceClient.create(), "my-project");
LoomScript script = new LoomLoader().load("workflow.loom");
HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), new DefaultLLMClientFactory(System::getenv, secrets));
executor.setSecretStore(secrets);          // `secret.NAME` in the script, and the models' keys, now come from the vault
executor.initialize();
executor.executeWorkflow("Main", Map.of("topic", "home composting"));
executor.shutdown();
```

Put the host in its own Maven module with the vault's client library as a dependency, and give it the identity the platform provides (Application
Default Credentials, workload identity); no key is written down anywhere. `SecretMetadata.allowing("host")` restricts a secret to the hosts it
may be sent to. Use `ChainedSecretStore.of(vault, EnvSecretStore.system())` if some keys should fall back to the environment. Write tests for the host in the application's own stack, with a stand-in for the vault and never a real key; they run with the project's build, not `weave`.

## Built-in tools when deployed (webhook, email, http, file, shell, sql)

If any agent uses one of the built-in tools, the workflow needs more than keys when it goes live: where each credential comes from, who approves, what survives a restart, and a free
check on the machine it will run on. **The agent that built the workflow writes this down as a "Deploying" section in the project's README**, one line per name the script needs, saying
what it is for and how to set it. The names come from the script itself: `weave check main.loom --no-env` lists them under "not set yet", and it is free.

**One entry file per environment, the same workflows underneath.** Tools are declared once in the file you run, so a development entry and a deployed entry can differ in one block and
share every agent and workflow (`import`; see the chapter on splitting files):

```loom file=deploy/flows/digest.loom
agent Notifier { model: "gemini-2.5-flash"  system: "You write the daily digest and send it by email."  tools: [Mail] }

workflow Digest(topic_text) {
    delegate "Write and send a short digest about {topic_text}" to Notifier -> sent_text
    note "{sent_text}"
}
```

```loom file=deploy/dev.loom
import "flows/digest.loom"

budget { tokens: 50000  calls: 10 }

// Development: each message is written to outbox/ as an .eml file and nothing is sent.
tool Mail { use: email  outbox: "outbox"  from: "digest@example.com"  to: "team@example.com" }
```

```loom file=deploy/prod.loom
import "flows/digest.loom"

budget { tokens: 50000  calls: 10 }

// Deployed: real mail; the server and the credentials come from the environment or the secret store, never from this file.
tool Mail {
    use: email
    host: env.SMTP_HOST  port: 587  security: starttls
    username: env.SMTP_USER  password: env.SMTP_PASSWORD
    from: "Digest <digest@example.com>"
    to: env.DIGEST_TO
    max_per_run: 5
}
```

Run the development entry until it behaves, then `weave check prod.loom --no-env` shows `DIGEST_TO`, `GEMINI_API_KEY` (the model's own key), `SMTP_HOST`, `SMTP_PASSWORD` and `SMTP_USER` as the names the deployer must supply. The development entry needs only the model's key.

**What to settle for every tool, whatever its kind:**

1. **Credentials are `env.NAME` or `secret.NAME`**, never literals (a literal is a load error), and the webhook URL counts as a credential. At deploy they come from the service's environment
   (a systemd `EnvironmentFile`, a container secret, a platform secret injected as variables), from the encrypted secret store (`--secrets`), or from a vault through a Java host (above).
   Scheduled runs started by the operating system (`weave triggers install`) do not see your shell: give them an environment file outside the project, `chmod 600`, with `--env-file`.
   Never the project's `.env`.
2. **Approvals need somebody to answer.** An agent with `approve: [...]` on a deployed workflow has no console: choose how a person is asked (`--ask-via telegram`, or a host that supplies
   a `HumanInterface`; see "Answering from a phone" in the Loom reference). A `shell` tool must be under `approve:` or say `unattended: true`, or the script does not load.
3. **Use a durable journal** (`--journal <folder>`). Tools that change something (`webhook`, `email`, `shell`, `file` writes, `http` other than GET) are recorded, so a restart does not
   send the message twice; with the in-memory journal that holds only within one process.
4. **Check on the machine it will run on, without `--no-env`:** `weave check prod.loom` fails naming every variable that is not set there; it contacts nothing and costs nothing. Then
   `weave audit prod.loom --fail-on medium`, then a capped smoke run that goes to a test sink first (the email `outbox`, a test webhook URL), then the real recipients.

| Tool | Supply when deployed | Decide | Watch out for |
|---|---|---|---|
| `webhook` | `url: env.NAME`: the URL is the secret, so make one per environment | `format` (slack, discord, teams, json); `retries`; `idempotency: true` if the receiver removes duplicates | https only; no private or metadata addresses unless `allow_private`; behind a proxy the address check covers only what Loom resolves |
| `email` | SMTP `host`, `port`, `security`, `username`, `password`; a `from` address the provider lets you send as | fixed `to` or `allow_to`; `max_per_run`; `outbox` while developing | needs STARTTLS or SSL; Gmail API and OAuth are not covered (use an MCP server) |
| `http` | `base_url`; `auth_value` as a secret | `allow_paths`; `methods` (put `approve:` on any that is not GET); `hosts` | the agent chooses only a path below `base_url` |
| `file` | a `root` folder the service can write, inside the script's directory | `mode`; `allow` patterns; `overwrite` | the folder must survive a restart (a volume); hidden files, the journal and the trigger store are refused |
| `shell` | every program in `allow` installed on the host's `PATH` (looked up at load: a container image must contain them) | `approve:` or `unattended: true`; `env_pass`; run as a user that can do little harm | not supported on Windows; an allowed program is trusted with any arguments |
| `sql` | `url`, `user`, `password` as secrets; the JDBC driver (PostgreSQL ships in the `weave` jar, others go on the class path) | `max_rows` | give it a database user that is itself read-only: the checks guard mistakes, not a determined attacker |

## If you skipped evaluation

If the README says `Evaluation: skipped`, this is the moment to say it once: **no evaluation of this workflow exists**, so nothing has
measured whether its answers are good, only that it runs and passes `weave check` and `weave audit`. You can carry on, and the caps and
approvals above still apply. If you want a safety net after all, `weave eval <script> --init` creates a dataset in a minute, and
`weave eval <script> --mock` is free.

## Gate

The smoke run and the first real run cost about what the model said, no limit was hit unexpectedly, the audit trail and trace look right, and provider limits and caps are set.
