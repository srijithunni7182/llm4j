#!/usr/bin/env bash
# A stand-in for java: `-version` succeeds, anything else records its pid and hangs.
if [ "$1" = "-version" ]; then echo 'openjdk version "17"' >&2; exit 0; fi
echo $$ > "${SLOW_JAVA_PID_FILE:?}"
exec sleep 30
