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

**Someone deploying the application** (a server, shared or production keys). Never a `.env` there. Use the encrypted secret store, a one-time setup:

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

## If you skipped evaluation

If the README says `Evaluation: skipped`, this is the moment to say it once: **no evaluation of this workflow exists**, so nothing has
measured whether its answers are good, only that it runs and passes `weave check` and `weave audit`. You can carry on, and the caps and
approvals above still apply. If you want a safety net after all, `weave eval <script> --init` creates a dataset in a minute, and
`weave eval <script> --mock` is free.

## Gate

The smoke run and the first real run cost about what the model said, no limit was hit unexpectedly, the audit trail and trace look right, and provider limits and caps are set.
