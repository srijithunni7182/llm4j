# G5: real runs, outside the test harness

Real `weave` processes (a new JVM each), the real tools, real files, and a real kill. Only the *model* and the
outside services are stand-ins (`scripts/g5/fake_services.py`: an Ollama-protocol model server with a script for
the digest agents, a news API, and a recording webhook), because no model key or accounts are available here.
The sample's `model: "gemini-2.5-flash"` lines were swapped for `ollama/fake`; nothing else in the sample changed.
Run with `scripts/g5/run_g5.sh`; transcripts are in `g5/`.

| # | Run | Result | Transcript |
|---|---|---|---|
| R1 | The digest sample, two days, separate journals | **As expected.** Day 1: no `last.json` yet (the tool says so); `digest.md`, `last.json` and one `.eml` written. Day 2: the Collector read day 1's `last.json` (`{"reported": ["Alpha story, day 1"]}`), then wrote day 2's files; two `.eml` in total | `R1-sample.txt` |
| R2 | `kill -9` after Slack was told, then the same command again | **As expected.** One Slack post before the kill. On resume the three finished steps were reused, the Slack call showed `tool effect: Slack … replayed` and "(already done in an earlier attempt)". **Slack posts in total: 1.** One `.eml` | `R2-crash-resume.txt` |
| R3 | Each tool by hand, with attack inputs I chose (not taken from the test suites) | **As expected for all six.** See below | `R3-*.txt` |
| R4 | `schedule sync`, `triggers list`, `triggers install` (plan only) | **As expected.** The cron and zone are listed with the next run; the install shows the systemd files and commands and ends "Nothing changed. Run again with --apply to install it."; nothing is written under `HOME` | `R4-schedule.txt` |
| R5 | `--max-tokens 1` | **As expected.** `budget_refused`; **0 model requests and 0 news requests** reached the stand-in; no files created | `R5-budget.txt` |
| R6 | Secrets sweep (also G9b) | **As expected.** See `G9-safety.md` | `R6-*.txt` |

### R3 in detail (attacks chosen for this run)

| Tool | Good calls | Attacks, all refused with `Error:` | World afterwards |
|---|---|---|---|
| `webhook` | a plain message | a title with a line break; an empty text; a `url` argument (ignored: the message still went to the fixed hook) | the server saw exactly the two intended POSTs, both to the fixed path |
| `http` | `GET /v0/item/101.json` | a tab at the end of the path; `..%2f..%2f` in the path; `?admin=1` in the path; `//v0/…`; `DELETE`; a path outside `allow_paths` | **one** request reached the server |
| `file` | write `made.md`; append to `keep.md` | `../canary.txt`; `/etc/hostname`; `.hidden.md`; `sub/../../canary.md` | `canary.txt` untouched; only `made.md` was added |
| `shell` | `echo hello '$(date)' '; touch pwned'` printed those arguments literally | `ls`; `echo$IFS`; `/usr/bin/echo`; an argument with a line break | no `pwned` file |
| `email` (outbox) | one message to `team@example.com` | a recipient outside `*@example.com`; a subject with `Bcc:` after a line break; a sub-domain recipient | one `.eml` |
| `sql` (H2 file database) | `SELECT name …` returned Asha and Ben | `SELECT 1; DROP TABLE people`; `DELETE …`; `SELECT … INTO OUTFILE`; `FILE_READ('/etc/hostname')`; a quote-injection value passed as a parameter (matched nothing, as data) | the table still has 2 rows |

### Not run

- `triggers install --apply`: the container has neither `crontab` nor a systemd user session, so only the plan was
  exercised (R4), plus the unit tests of the installers. The `--apply` path predates this work.
- A real model and real Slack/SMTP/PostgreSQL: see the optional live checks (L1–L4), not run.
