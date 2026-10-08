# run.sh
if [ -z "$GEMINI_API_KEY" ]; then
  echo "Error: GEMINI_API_KEY environment variable is not set."
  exit 1
fi

LOOM_JAR="../../target/ai-agent4j-loom-5.0.jar"
TOPIC="${1:-The Future of Neuro-Symbolic AI}"

java -cp "$LOOM_JAR:../../target/lib/*" \
  io.github.llm4j.loom.cli.WeaveCLI run main.loom \
  --workflow GenerateContent \
  --input topic="$TOPIC"
