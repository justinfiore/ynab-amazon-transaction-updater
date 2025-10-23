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
./build/install/YNABAmazonTransactionUpdater/bin/YNABAmazonTransactionUpdater $*

echo ""
echo "Application finished." 