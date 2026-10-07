#!/usr/bin/env bash
# Proves the Maven test module that `weave init <template> --with-java-tests` adds really works, with real Maven:
#   1. the generated project's tests compile and pass;
#   2. when test classes exist but nothing runs, the build FAILS (an old surefire says BUILD SUCCESS for "Tests run: 0"; this pom must not).
# Usage: scripts/verify-java-tests-template.sh [template]    (default: pipeline)
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
template="${1:-pipeline}"
echo "== installing the current Loom into the local Maven repository"
mvn -q -f "$root/pom.xml" -pl loom/ai-agent4j-loom -am -DskipTests -Djacoco.skip=true install
jar="$(ls "$root"/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -n 1)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
echo "== weave init $template (a Maven project)"
java -jar "$jar" init "$template" "$work/project" >/dev/null
cd "$work/project"
for f in pom.xml src/main/resources/main.loom src/test/resources/eval/golden/workflow.yaml src/test/java/starter/ScriptWiringTest.java; do [ -f "$f" ] || { echo "FAIL: the project has no $f"; exit 1; }; done
echo "== weave finds the dataset and the script from the project root"
java -jar "$jar" eval src/main/resources/main.loom --check >/dev/null || { echo "FAIL: weave eval --check"; exit 1; }
java -jar "$jar" next >/dev/null 2>&1 || true
echo "== mvn test (must pass)"
mvn -q -B test 2>&1 | tail -n 15
mvn -B test 2>&1 | grep -E "Tests run:.*Fail" | tail -n 3
echo "== with test classes that run nothing, mvn test must FAIL"
rm -f src/test/java/starter/*.java
printf 'package starter;\nclass EmptyTest { }\n' > src/test/java/starter/EmptyTest.java
if mvn -q -B test >"$work/empty.log" 2>&1; then
  echo "FAIL: a build with no tests passed"; tail -n 15 "$work/empty.log"; exit 1
fi
grep -E "No tests|no tests|failIfNoTests" "$work/empty.log" | head -n 3 || true
echo "ok: the generated tests pass, and a build that finds test classes but runs none fails"
