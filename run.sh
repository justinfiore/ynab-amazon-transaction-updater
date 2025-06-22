#!/bin/bash

echo "Building YNAB Amazon Transaction Updater..."
./gradlew build

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Running YNAB Amazon Transaction Updater..."
echo ""
java -jar build/libs/YNABAmazonTransactionUpdater-1.0.0.jar

echo ""
echo "Application finished." 