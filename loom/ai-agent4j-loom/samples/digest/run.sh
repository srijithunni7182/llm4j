#!/usr/bin/env bash
# Runs the daily digest once, then shows how to schedule it. Run from this directory.
#   GEMINI_API_KEY=...  ./run.sh              # the no-account version
#   GEMINI_API_KEY=... SLACK_WEBHOOK=...  ./run.sh slack
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "Set GEMINI_API_KEY (the agents use Gemini)." >&2
  exit 1
fi
export HN_URL="${HN_URL:-https://hacker-news.firebaseio.com}"

LOOM_JAR="../../target/ai-agent4j-loom-5.0.jar"
weave() { java -cp "$LOOM_JAR:../../target/lib/*" io.github.llm4j.loom.cli.WeaveCLI "$@"; }

SCRIPT=digest.loom
WORKFLOW=DailyDigest
if [ "${1:-}" = "slack" ]; then
  : "${SLACK_WEBHOOK:?Set SLACK_WEBHOOK for the Slack version}"
  SCRIPT=digest-slack.loom
  WORKFLOW=DailyDigestWithSlack
fi

weave check "$SCRIPT"
weave run "$SCRIPT" --workflow "$WORKFLOW" --input topic="AI agents" --journal "runs/$(date +%F)" --trace

echo
echo "Digest:   digest/digest.md"
echo "Mail:     outbox/*.eml (nothing was sent)"
echo
echo "To run it every morning on this machine (shows what it would do; add --apply to do it):"
echo "  weave schedule sync $SCRIPT --store ~/.loom/triggers"
echo "  weave triggers install ~/.loom/triggers --env-file ~/.loom/env"
