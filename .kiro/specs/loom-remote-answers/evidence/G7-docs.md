# G7: docs

- `ChannelGuideTest` (passing in the G1 runs): the `loom` block in the "Answering from Your Phone" section of `LOOM_GUIDE.md` loads; every `weave` command and option shown in the section exists on the real command line (`answer`, `questions`, `tick`, `daemon`, `triggers`, `run`, with `--ask-via`, `--all`); the `channel.json` example parses into the configuration it describes; the Earned Autonomy section links to the new one; `weave --help` lists `answer` and `questions`, and `--ask-via` is on `run`, `resume`, `tick` and `daemon`.
- The guide says what is safe (allowlist, the code for approvals, blind `watch` messages, plain text, the token) and what to know (bot chats are not end-to-end encrypted; use a private chat).
- Also updated: `LOOM_PROMPT.md` (the script never names a channel), `src/loom/README.md`, the root `README.md` and `WHY_LOOM.md` (item 11).
- No sample workflow directory was added (V7.3).
