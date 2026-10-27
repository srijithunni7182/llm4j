#!/usr/bin/env bash
# Writes docs/COMMAND_REFERENCE.md from the commands themselves (weave man --all --format markdown).
# ManPageTest fails when the file is out of date. Run after changing a command, an option or a
# page under src/loom/ai-agent4j-loom/src/main/resources/man/.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MOD=$ROOT/src/loom/ai-agent4j-loom
CP=${WEAVE_CP:-$MOD/target/classes:$MOD/target/lib/*}
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI man --all --format markdown > "$ROOT/docs/COMMAND_REFERENCE.md"
echo "wrote docs/COMMAND_REFERENCE.md"
