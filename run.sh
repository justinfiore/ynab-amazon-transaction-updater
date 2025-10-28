#!/bin/bash

echo "Building YNAB Amazon Transaction Updater..."
./gradlew install

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Running YNAB Amazon Transaction Updater..."
echo ""

# Find the script in any subdirectory of build/install
SCRIPT=$(find build/install -type f -path "*/bin/*" ! -name "*.bat" | head -n 1)

if [ -z "$SCRIPT" ]; then
    echo "Error: Could not find executable script in build/install/*/bin/"
    exit 1
fi

"$SCRIPT" $*

echo ""
echo "Application finished." 