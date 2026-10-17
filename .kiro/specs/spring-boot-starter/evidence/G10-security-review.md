# G10: security review

A read-only review of the starter and `loom.host` code against base `185720cd`, by an agent with no part in writing it. No HIGH finding.

| # | Finding | Rating | Outcome |
|---|---|---|---|
| 1 | An explicit `runId` can reuse a finished run's id and replay its journal | MEDIUM | **Documented, not changed.** Reuse of an id is a designed, tested behaviour (V8.5: a finished run can be started again under its id, and a run that suspended is resumed through the same id). The id is application input: `RunRequest` Javadoc now says an application must not let its users pick ids. Without one the host uses a random UUID. |
| 2 | `resume` has no tie to the person who started the run | MEDIUM | **Documented.** Who may resume or answer a run is the application's authorisation decision (it holds the session and the run id); the host deliberately stores no identity beyond the free-text `origin`, and no credential. Invoker credentials were checked and are in no record, file, row or message (V8.9, V10.7). |
| 3 | A run id could escape or collide as a folder name in the file store | MEDIUM | **Fixed.** `RunRequest` now refuses ids that are not 1–100 of `A-Za-z0-9._@-` starting with a letter or digit; `RunRegistry.safe` refuses rather than rewrites; ids the host builds for triggers go through `RunRegistry.idFrom`. Tests: `HostPartsTest.aRunIdThatCouldNotBeAFolderNameIsRefused`. |
| 4 | The file `AnswerBook` and run registry lock only within one JVM | LOW | **Documented.** A file store is for one process (its folder is the `weave` layout); use `jdbc` for several. |
| 5 | Guarded tools skipped the library's `agentProblem` rule, so a `shell` tool was usable by every agent unasked | MEDIUM | **Fixed.** A tool whose kind says an agent needs approval to use it (`shell`) is refused at start-up unless `options.unattended=true`. Test: `SettingsTest.aToolThatRunsProgramsMustSayNobodyWillBeAsked`. |
| 6 | `llm4j.loom.script` and `.loot` could be an `http(s):` address | LOW | **Fixed.** Refused with a message; scripts and tool-reach files are code and load only from `classpath:` or `file:`. Test: `SettingsTest.scriptsAndLootFilesAreNeverFetchedOverTheNetwork`. |
| 7 | A tool-creation error message could echo an option value | LOW | **Accepted.** Options come from the application's own configuration and the message is shown to its developer at start-up; secret-valued options are `-secret` names, not values. |

Checked and found sound: the run page refuses a non-loopback bind (and the starter asks for the admin password first); the security audit gate fails closed; every SQL statement is a prepared statement over constant table names; keys are masked in `toString` and configuration metadata; no polymorphic deserialization.
