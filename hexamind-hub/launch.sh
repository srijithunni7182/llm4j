#!/bin/bash

# Hexamind Hub Launcher
# Convenient script to start the platform with your API key

echo "🤖 Hexamind Hub"
echo "======================================"
echo ""

# Set API key and Search CX
export GOOGLE_API_KEY="AIzaSyC1Kxs2UCzUcxpQFQ6tP918RdGQA3_rt1A"
export GOOGLE_SEARCH_CX="f31333d48a729444c" # Add your Google Custom Search Engine ID here (cx parameter)

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
