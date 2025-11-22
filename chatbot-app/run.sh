#!/bin/bash

# Simple Chatbot Launcher Script

echo "🤖 LLM4J Chatbot Launcher"
echo "========================="
echo

# Check for API keys
if [ -z "$OPENAI_API_KEY" ] && [ -z "$ANTHROPIC_API_KEY" ] && [ -z "$GOOGLE_API_KEY" ]; then
    echo "❌ No API key found!"
    echo
    echo "Please set one of the following environment variables:"
    echo "  export OPENAI_API_KEY='your-key-here'"
    echo "  export ANTHROPIC_API_KEY='your-key-here'"
    echo "  export GOOGLE_API_KEY='your-key-here'"
    echo
    exit 1
fi

echo "✓ API key found"
echo "Starting chatbot..."
echo

# Run the chatbot
cd "$(dirname "$0")"
mvn -q exec:java
