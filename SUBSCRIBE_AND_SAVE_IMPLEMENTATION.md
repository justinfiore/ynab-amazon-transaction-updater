# Subscribe and Save Implementation Summary

## Overview
Successfully implemented support for Amazon Subscribe and Save transactions, which previously couldn't be matched because they don't generate regular order confirmation emails.

## Key Features Implemented

### 1. Email Parsing for Subscribe and Save
- **Email Detection**: Identifies Subscribe and Save emails from `no-reply@amazon.com` with subject "review your upcoming delivery"
- **Date Extraction**: Parses delivery dates in format "Arriving by [Day], [Month] [Date]"
- **Price Extraction**: Extracts individual item prices using pattern `*$XX.XX*`
- **Order ID Generation**: Creates unique IDs with "SUB-" prefix

### 2. Automatic Integration
- Subscribe and Save emails are automatically detected alongside regular Amazon orders
- No additional configuration needed beyond email setup
- Seamlessly integrated into existing order fetching workflow

### 3. YNAB Transaction Memo Formatting
- Subscribe and Save transactions get **"S&S: "** prefix in memos
- Example: `S&S: Tide Laundry Detergent` or `S&S: 3 items: Tide, Charmin, Bounty`
- Removes redundant "(Subscribe & Save)" suffix from product names
- Preserves existing memo content when present

### 4. Configuration
No additional configuration needed! Subscribe and Save emails are automatically detected when email fetching is enabled:
```yaml
amazon:
  email: "your_email@example.com"
  email_password: "your_app_password"
```

## Files Modified

### Core Implementation
- `src/main/groovy/com/ynab/amazon/service/AmazonOrderFetcher.groovy`
  - Added Subscribe and Save email patterns
  - Implemented `parseSubscriptionFromEmail()` method
  - Enhanced email detection logic

- `src/main/groovy/com/ynab/amazon/service/AmazonService.groovy`
  - Integrated subscription service
  - Added subscription orders to main order flow
  - Updated configuration validation

- `src/main/groovy/com/ynab/amazon/service/TransactionMatcher.groovy`
  - Added "S&S: " prefix for Subscribe and Save memos
  - Strips "(Subscribe & Save)" suffix from product names
  - Added `&` to allowed memo characters

- `src/main/groovy/com/ynab/amazon/config/Configuration.groovy`
  - Added subscription configuration properties
  - Updated validation logic

### New Files Created
- `src/test/groovy/com/ynab/amazon/service/EmailTestHelper.groovy`
  - Extracts text/plain content from .eml files
  - Parses email metadata (subject, from, date)
  - Used for testing with real email content

### Test Files
- `src/test/groovy/com/ynab/amazon/service/AmazonOrderFetcher_SubscribeAndSave_UT.groovy`
  - Tests using real .eml files
  - Email content parsing validation
  - Regex pattern verification

- `src/test/groovy/com/ynab/amazon/service/AmazonService_SubscribeAndSave_IT.groovy`
  - Integration tests for complete flow
  - Tests with mixed data sources
  - Memo formatting validation

- Updated `src/test/groovy/com/ynab/amazon/service/TransactionMatcher_UT.groovy`
  - Added S&S memo prefix tests
  - Verified memo formatting for subscriptions

### Test Resources
Real Subscribe and Save email files used for testing:
- `src/test/resources/Price changes_ review your upcoming delivery-July 11.eml`
- `src/test/resources/Price changes_ review your upcoming delivery-Aug 11.eml`
- `src/test/resources/Price changes_ review your upcoming delivery-Sept 30.eml`
- `src/test/resources/Price changes_ review your upcoming delivery-Oct 30.eml`

### Documentation
- `SUBSCRIBE_AND_SAVE.md` - User guide for Subscribe and Save features
- `config.example.yml` - Updated with notes about automatic Subscribe and Save detection

## Test Results
All tests passing:
- **Unit Tests**: Tests completed successfully
- **Integration Tests**: Subscribe and Save integration tests passing
- Tests use real email content from .eml files for validation

## Usage

### Email-Based (Automatic)
```yaml
amazon:
  email: "your_email@example.com"
  email_password: "your_app_password"
```

That's it! Subscribe and Save emails are automatically detected and processed.

## Technical Details

### Email Parsing Patterns
- **Delivery Date**: `(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \d{1,2})`
- **Price**: `\*\$([0-9]+\.?[0-9]{0,2})\*`
- **Subject**: Contains "review your upcoming delivery"
- **Sender**: `no-reply@amazon.com` (in forwarded email content)

### Order ID Format
Subscribe and Save orders use the format: `SUB-YYYYMMDD-XXXXXXXX`
- Prefix: "SUB-"
- Date: Email sent date in YYYYMMDD format
- Suffix: First 8 alphanumeric characters from subject

### Amount Handling
All Amazon charges are stored as negative values in YNAB (e.g., -24.99 for a $24.99 charge).

## Benefits
1. **Automatic Matching**: Subscribe and Save transactions now match automatically
2. **Clear Identification**: "S&S: " prefix makes subscriptions easy to identify in YNAB
3. **Flexible Data Sources**: Supports email parsing, CSV, and JSON inputs
4. **Comprehensive Testing**: All functionality validated with real email content
5. **Easy Setup**: Simple configuration options for different use cases
