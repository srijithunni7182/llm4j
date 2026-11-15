#!/usr/bin/env bash
# Expense Desk: a web application on llm4j. No model, no key and no network unless you pass --live.
#   ./run.sh                 (http://localhost:8787, offline stand-in models)
#   ./run.sh --port 9000     (another port)
#   ./run.sh --live          (the script's real models; their keys come from the environment)
# It needs the llm4j jars in ~/.m2: run `make setup` at the root of the checkout once.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -Djava.util.logging.config.file=logging.properties \
  -cp "target/classes:$(cat target/classpath.txt)" io.github.llm4j.examples.expensedesk.ExpenseDesk "$@"
