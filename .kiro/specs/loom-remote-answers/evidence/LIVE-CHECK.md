# G10: live checks (not run)

Not run: they need a real Telegram bot and a machine that stays on, and the build environment has neither (outbound access is restricted, and a bot token should not be handed to it).

L1, about ten minutes on your own machine:

1. In Telegram, message **@BotFather**, `/newbot`, keep the token. Open the bot and press Start. Message **@userinfobot** for your chat id.
2. On the machine that runs Loom: `export TELEGRAM_BOT_TOKEN=<token> TELEGRAM_CHAT_IDS=<your id>`.
3. Run a small question workflow with a journal and a store:
   `weave run digest.loom -w Digest -i topic=test --journal runs/live --store runs/.loom-triggers --ask-via telegram`
   (the run pauses and the question arrives on your phone).
4. Reply `yes` to that message. Then `weave tick runs/.loom-triggers --ask-via telegram` (or leave `weave daemon runs/.loom-triggers --ask-via telegram` running).
5. Expect: "Recorded 1 answer(s)", the run completes, and your phone shows "Recorded: yes for <code>".
6. Then a `decision` in `watch`: the message must show the case and the choices but no proposal.

L2 (optional): install the heartbeat on a small server (`weave triggers install runs/.loom-triggers --apply`) and answer from your phone while your laptop is off.
