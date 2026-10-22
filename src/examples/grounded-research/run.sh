#!/usr/bin/env bash
# Asks one question. Usage: ./run.sh "question"   (needs PERPLEXITY_API_KEY and ANTHROPIC_API_KEY)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
weave="${WEAVE_JAR:-$here/../../loom/ai-agent4j-loom/target/ai-agent4j-loom-6.0.0-cli.jar}"
cd "$here"
java -jar "$weave" run research.loom -i question="${1:-What is the WHO guidance on daily sodium intake?}" "${@:2}"
