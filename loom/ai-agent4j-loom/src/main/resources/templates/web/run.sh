#!/bin/sh
# Starts the web page. sh run.sh --mock costs nothing; sh run.sh calls the real model (needs the key in .env) and spends money.
set -e
cd "$(dirname "$0")"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" web.App "$@"
