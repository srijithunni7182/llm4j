#!/bin/bash

# Script to run the Sarvam AI TTS example

# Export the Sarvam API key (WARNING: do not commit this to version control)
export SARVAM_API_KEY="sk_zfd12mzm_EEC76orbDXyBLt4NU6IAAyyi"

# Compile and run the Java example
mvn compile exec:java -Dexec.classpathScope=test -Dexec.mainClass="io.github.llm4j.examples.SarvamTTSExample"
