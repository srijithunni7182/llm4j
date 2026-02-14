#!/bin/bash

# Configuration
PROJECT_ROOT=$(pwd)
BACKEND_DIR="$PROJECT_ROOT/tech-lead-dojo"
FRONTEND_DIR="$PROJECT_ROOT/tech-lead-dojo/frontend"

echo "🚀 Starting Tech Lead Dojo..."

# Function to kill background processes on exit
cleanup() {
    echo "🛑 Shutting down..."
    kill $(jobs -p)
    exit
}

trap cleanup SIGINT SIGTERM

# Start Backend
echo "📦 Starting Backend (Spring Boot)..."
cd "$BACKEND_DIR" && mvn spring-boot:run &

# Start Frontend
echo "🎨 Starting Frontend (Vite)..."
cd "$FRONTEND_DIR" && npm run dev &

echo "✨ Both services are starting up!"
echo "Backend: http://localhost:8080"
echo "Frontend: http://localhost:5173"
echo "Press Ctrl+C to stop both."

# Wait for both background processes
wait
