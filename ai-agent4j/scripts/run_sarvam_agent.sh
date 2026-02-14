#!/bin/bash

# Ensure SARVAM_API_KEY is set
if [ -z "$SARVAM_API_KEY" ]; then
  echo "Error: SARVAM_API_KEY environment variable is not set."
  exit 1
fi

echo "Running Sarvam Voice Agent Example..."
mvn exec:java \
  -Dexec.mainClass="io.github.llm4j.examples.SarvamVoiceAgentExample" \
  -Dexec.classpathScope=test
