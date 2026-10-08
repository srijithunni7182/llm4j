#!/bin/bash

# Define the target file
FILE="src/main/resources/static/index.html"

echo "🔍 Verifying Frontend Structure in $FILE..."

# 1. Check for 3-Column Grid Layout
if grep -q "grid-template-columns: 280px 1fr 280px;" "$FILE"; then
    echo "✅ [PASS] 3-Column Layout CSS found."
else
    echo "❌ [FAIL] 3-Column Layout CSS NOT found."
    exit 1
fi

# 2. Check for Neural Stream Sidebar Container
if grep -q "id=\"knowledgeStreamContainer\"" "$FILE"; then
    echo "✅ [PASS] Neural Stream Container found."
else
    echo "❌ [FAIL] Neural Stream Container NOT found."
    exit 1
fi

# 3. Check for Agent Rendering Logic
# We look for the loop that creates agent cards
if grep -q "agentsConfig.forEach(agent => {" "$FILE"; then
    echo "✅ [PASS] Agent Rendering Logic found."
else
    echo "❌ [FAIL] Agent Rendering Logic NOT found."
    exit 1
fi

# 4. Check for Aggregated Stats Logic
if grep -q "/api/user/stats" "$FILE"; then
    echo "✅ [PASS] Aggregated Stats Logic found."
else
    echo "❌ [FAIL] Aggregated Stats Logic NOT found."
    exit 1
fi

echo "🎉 All Frontend Static Checks Passed!"
