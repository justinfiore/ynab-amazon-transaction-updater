#!/bin/bash

echo "Building Budget Assistant for YNAB..."
./gradlew installDist

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Running Budget Assistant for YNAB..."
echo ""

exec "$(dirname "$0")/build/install/ynab-budget-assistant/bin/ynab-budget-assistant" "$@"
