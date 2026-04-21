#!/bin/bash
if [ -f "secrets.sh" ]; then
    echo "Loading secrets..."
    source secrets.sh
else
    echo "Warning: secrets.sh not found!"
fi

echo "Starting Gmail MCP App..."
mvn spring-boot:run
