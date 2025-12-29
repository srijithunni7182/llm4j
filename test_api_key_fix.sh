#!/bin/bash
# Ensure keys are set in environment
if [ -z "$GOOGLE_API_KEY" ]; then
    echo "Error: GOOGLE_API_KEY not set"
    exit 1
fi

echo "Testing API key fix: What is the status of flight AA100?"
echo "What is the status of flight AA100?" | java -cp aviation-chatbot/target/aviation-chatbot-1.0-SNAPSHOT.jar io.github.llm4j.aviation.ChatbotCLI 2>&1 | grep -E "(OpenAPI Tool|Bot:|access_key)" | head -20
