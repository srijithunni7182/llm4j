#!/usr/bin/env bash
# Expense Desk: a web application on llm4j. No model, no key and no network unless you pass --live.
#   ./run.sh                 (http://localhost:8787, offline stand-in models)
#   ./run.sh --port 9000     (another port)
#   ./run.sh --live          (the script's real models; their keys come from the environment)
#   ./run.sh --fabric http://127.0.0.1:9090 --name expense-desk   (report every run to a Fabric server; see the README)
# It needs the llm4j jars in ~/.m2: run `make setup` at the root of the checkout once.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# the inspect add-on, when this checkout has built it: needed only with --fabric
INSPECT=$(ls ../../loom/loom-inspect/target/loom-inspect-[0-9]*.jar ../../loom/loom-inspect-core/target/loom-inspect-core-[0-9]*.jar 2>/dev/null | grep -v sources | tr '\n' ':' || true)
exec java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -Djava.util.logging.config.file=logging.properties \
  -cp "target/classes:$(cat target/classpath.txt):$INSPECT" io.github.llm4j.examples.expensedesk.ExpenseDesk "$@"
