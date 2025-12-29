#!/bin/bash

# Hexamind Hub Launcher
# Convenient script to start the platform with your API key

echo "🤖 Hexamind Hub"
echo "======================================"
echo ""

# Set API key and Search CX
# Try to source secrets from local file not in git
if [ -f "secrets.sh" ]; then
    source "secrets.sh"
fi

# Check variables
if [ -z "$GOOGLE_API_KEY" ]; then
    echo "❌ Error: GOOGLE_API_KEY is not set."
    echo "Please set it using: export GOOGLE_API_KEY=your_key"
    echo "Or create a secrets.sh file with the export."
    exit 1
fi

if [ -z "$GOOGLE_SEARCH_CX" ]; then
    echo "⚠️ Warning: GOOGLE_SEARCH_CX is not set. Web search capabilities may be limited."
fi

# Navigate to platform directory
cd "$(dirname "$0")"

echo "📦 Building platform..."
mvn clean package -DskipTests -q

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo ""
    echo "🚀 Starting Hexamind Hub..."
    echo "📍 Open your browser to: http://localhost:8080"
    echo ""
    echo "Press Ctrl+C to stop the server"
    echo "======================================"
    echo ""
    
    # Run the application
    mvn spring-boot:run
else
    echo "❌ Build failed. Please check the errors above."
    exit 1
fi
