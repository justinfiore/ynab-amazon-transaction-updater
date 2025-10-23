# Product Overview

YNAB Amazon Transaction Updater is a Groovy application that automatically enhances YNAB (You Need A Budget) transactions by matching them with Amazon order details and updating transaction memos with product summaries.

## Core Functionality

- Fetches transactions from YNAB API within a configurable lookback period
- Reads Amazon order data from CSV exports or email parsing
- Uses intelligent matching algorithms to correlate YNAB transactions with Amazon orders
- Updates transaction memos with meaningful product descriptions
- Tracks processed transactions to prevent duplicates
- Supports dry-run mode for safe testing

## Key Features

- **Smart Matching**: Uses weighted scoring (amount 40%, date 30%, payee 20%, memo 10%) with confidence thresholds
- **Multiple Data Sources**: Supports both CSV file input and email parsing for Amazon orders
- **Safety First**: Dry-run mode, transaction tracking, and confidence-based filtering
- **Configurable**: YAML-based configuration with sensible defaults
- **Robust**: Comprehensive error handling and logging

## Target Users

Personal finance users who shop frequently on Amazon and want better transaction categorization and tracking in YNAB without manual memo updates.