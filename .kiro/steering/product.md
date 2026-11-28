# Product Overview

Budget Assistant for YNAB is a Groovy application that matches YNAB transactions with Amazon, Walmart and Sheetz purchases, with separately guarded categorization. Costco and Sam's Club support is planned, not implemented.

## Core Functionality

- Fetches transactions from YNAB API within a configurable lookback period
- Reads Amazon order data from CSV exports or email parsing
- Fetches Walmart order data using the opt-in native helper and a protected session
- Uses intelligent matching algorithms to correlate YNAB transactions with retailer orders
- Updates transaction memos with meaningful product descriptions
- Tracks processed transactions to prevent duplicates
- Supports dry-run mode for safe testing

## Key Features

- **Smart Matching**: 
  - Amazon: Weighted scoring (amount 40%, date 30%, payee 20%, memo 10%)
  - Walmart Single Transaction: Weighted scoring (amount 70%, date 20%, payee 10%)
  - Walmart Multi-Transaction: Weighted scoring (amount 50%, date proximity 30%, payee consistency 20%)
- **Multiple Data Sources**: 
  - Amazon: CSV file input and email parsing
  - Walmart: Optional native helper and protected session
- **Multi-Transaction Support**: Handles Walmart orders split into multiple charges
- **Safety First**: Dry-run mode, transaction tracking, and confidence-based filtering
- **Configurable**: YAML-based configuration with sensible defaults
- **Robust**: Comprehensive error handling and logging

## Target Users

Personal finance users who shop frequently at Amazon and/or Walmart and want better transaction categorization and tracking in YNAB without manual memo updates.
