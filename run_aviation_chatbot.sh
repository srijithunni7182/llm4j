#!/bin/bash

# Check for API Keys
if [ -z "$GOOGLE_API_KEY" ]; then
    echo "Error: GOOGLE_API_KEY is not set."
    exit 1
fi

if [ -z "$AVIATION_STACK_API_KEY" ]; then
    echo "Warning: AVIATION_STACK_API_KEY is not set. The chatbot might fail to fetch flight data."
fi

# Build Library
echo "Building Library..."
mvn install -DskipTests
if [ $? -ne 0 ]; then
    echo "Library build failed."
    exit 1
fi

# Build Backend
echo "Building Backend..."
cd aviation-chatbot
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "Backend build failed."
    exit 1
fi
cd ..

# Start Backend
echo "Starting Backend..."
java -jar aviation-chatbot/target/aviation-chatbot-1.0-SNAPSHOT.jar &
BACKEND_PID=$!

# Start Frontend
echo "Starting Frontend..."
cd aviation-chatbot-ui
npm start &
FRONTEND_PID=$!

echo "Backend running with PID $BACKEND_PID"
echo "Frontend running with PID $FRONTEND_PID"
echo "Access the app at http://localhost:4200"

# Trap to kill both on exit
trap "kill $BACKEND_PID $FRONTEND_PID" EXIT

wait
