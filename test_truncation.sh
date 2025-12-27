#!/bin/bash
export GOOGLE_API_KEY=AIzaSyDXoqOnt6omaz112O8E3vHFrYxS43N4jZ0
export AVIATION_STACK_API_KEY=9e8f58b752df93f3f396b8e8a86a3f2d

echo "Testing truncation: List all airports"
echo "List all airports" | java -cp aviation-chatbot/target/aviation-chatbot-1.0-SNAPSHOT.jar io.github.llm4j.aviation.ChatbotCLI 2>&1 | grep -E "(truncated due to length)"
