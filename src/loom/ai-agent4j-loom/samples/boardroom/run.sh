# run.sh
if [ -z "$GEMINI_API_KEY" ]; then
  echo "Error: GEMINI_API_KEY environment variable is not set."
  exit 1
fi

LOOM_JAR="../../target/ai-agent4j-loom-5.0.jar"
PROPOSAL="${1:-Should we migrate our entire stack to Neuro-Symbolic AI?}"

java -cp "$LOOM_JAR:../../target/lib/*" \
  io.github.llm4j.loom.cli.WeaveCLI run main.loom \
  --workflow EvaluateProposal \
  --input proposal="$PROPOSAL"
