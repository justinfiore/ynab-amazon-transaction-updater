# YNAB Amazon Transaction Updater

A Groovy application that automatically updates YNAB (You Need A Budget) transactions with Amazon order details by matching transactions and updating the memo field with product summaries.

## Features

- Connects to YNAB API to fetch transactions
- Reads Amazon order data from multiple sources:
  - Email parsing (automatic order confirmation emails)
  - Amazon Subscribe & Save delivery notifications
  - Amazon refund notifications
  - CSV export files
- Intelligently matches YNAB transactions with Amazon orders
- Updates transaction memos with product summaries
- Special handling for Subscribe & Save orders with "S&S:" prefix
- Automatic refund detection and processing with positive amounts
- Tracks processed transactions to avoid duplicates
- Supports dry-run mode for testing
- Configurable matching confidence thresholds
- Configurable lookback period for transactions

## Prerequisites

- Java 11 or higher
- Gradle (for building)
- YNAB API key
- YNAB Budget ID
- Amazon order history (via email or CSV export)
- (Optional) Email credentials for automatic order fetching

## Setup

### 1. Get Your YNAB API Key

1. Go to [YNAB Developer Settings](https://app.youneedabudget.com/settings/developer)
2. Click "New Token"
3. Give it a name (e.g., "Amazon Transaction Updater")
4. Copy the generated API key

### 2. Get Your YNAB Budget ID

1. Go to your YNAB budget in a web browser
2. The budget ID is in the URL: `https://app.youneedabudget.com/{BUDGET_ID}/...`
   - It will be a long string of letters and numbers
   - Example: `https://app.youneedabudget.com/abcdef12-3456-7890-abcd-ef1234567890/...`

### 3. Set Up Amazon Order Data Source

You have two options for providing Amazon order data:

**Option A: Email Parsing (Recommended)**

The application can automatically fetch Amazon orders from your email, including:
- Regular order confirmations
- Subscribe & Save delivery notifications
- Refund notifications (automatically detected and processed)

Requirements:
- Gmail or other IMAP-enabled email account
- App-specific password (not your main email password)

Setup:
1. Enable IMAP in your email settings
2. Generate an app-specific password:
   - Gmail: [App Passwords](https://support.google.com/accounts/answer/185833)
   - Outlook: [App Passwords](https://support.microsoft.com/account-billing/using-app-passwords-with-apps-that-don-t-support-two-step-verification-5896ed9b-4263-e681-128a-a6f2979a7944)
3. Configure in `config.yml` (see below)

**Option B: CSV Export (Fallback)**

If email parsing is not available or you prefer manual control:

1. Go to Amazon.com → Your Account → Your Orders
2. Manually copy order details into a CSV file with these columns:
   ```
   Order ID,Order Date,Title,Price,Quantity
   123-4567890-1234567,2024-01-15,Wireless Bluetooth Headphones,29.99,1
   ```
3. Save as `amazon_orders.csv` in your project directory
4. Configure the path in `config.yml`

Note: CSV export does not include Subscribe & Save orders automatically.

### 4. Configure the Application

Copy `config.example.yml` to `config.yml` and update the following values:

```yaml
ynab:
  api_key: "YOUR_ACTUAL_YNAB_API_KEY"
  budget_id: "YOUR_ACTUAL_YNAB_BUDGET_ID"
  base_url: "https://api.ynab.com/v1"

amazon:
  # Email parsing (recommended - automatically includes Subscribe & Save)
  email: "your_amazon_email@example.com"
  email_password: "your_app_password"  # Use app-specific password, not main password
  
  # Optional: If Subscribe & Save emails are forwarded from another account
  # forward_from_address: "some.email@example.com"  # Email address that forwards S&S emails to your inbox
  
  # CSV fallback (optional)
  csv_file_path: "amazon_orders.csv"

app:
  processed_transactions_file: "processed_transactions.json"
  log_level: "INFO"
  dry_run: true
  look_back_days: 30  # How many days back to search for transactions
```

## Building and Running

### Build the Application

```bash
./gradlew build
```

### Run the Application

```bash
./gradlew run
```

Or run the JAR directly:

```bash
java -jar build/libs/YNABAmazonTransactionUpdater-1.0.0.jar
```

## How It Works

1. **Configuration Loading**: Reads settings from `config.yml`
2. **YNAB Integration**: Fetches transactions from your YNAB budget within the lookback period
3. **Amazon Data**: Fetches order history from:
   - Email (automatic parsing of order confirmations and Subscribe & Save notifications)
   - CSV file (fallback or manual option)
4. **Transaction Matching**: Uses intelligent algorithms to match YNAB transactions with Amazon orders based on:
   - Amount similarity (40% weight)
   - Date proximity (30% weight)
   - Payee name matching (20% weight)
   - Memo content (10% weight)
5. **Memo Updates**: Updates matched transactions with product summaries:
   - Regular orders: "Product Name" or "3 items: Product A, Product B, Product C"
   - Subscribe & Save: "S&S: Product Name" or "S&S: 3 items: Product A, Product B, Product C"
   - Refunds: "REFUND: Product Name" with positive amounts for easy identification
6. **Tracking**: Maintains a list of processed transactions to avoid duplicates

## Amazon Subscribe & Save Support

The application automatically detects and processes Amazon Subscribe & Save orders from email notifications. These orders:

- Are identified by emails from `no-reply@amazon.com` with "review your upcoming delivery" in the subject
- Get a special "S&S:" prefix in YNAB memos for easy identification
- Parse delivery dates and individual item prices from email content
- Generate unique order IDs with "SUB-" prefix

For more details, see [SUBSCRIBE_AND_SAVE.md](SUBSCRIBE_AND_SAVE.md).

## Amazon Refund Support

The application automatically detects and processes Amazon refund notifications from email. Refunds are:

- Identified by emails from `return@amazon.com` with "Your refund for" in the subject
- Processed with positive amounts (opposite of regular orders) for accurate YNAB tracking
- Prefixed with "REFUND:" in transaction memos for easy identification
- Assigned order IDs with "RETURN-" prefix to distinguish from regular orders
- Matched to YNAB transactions using the same intelligent matching algorithm

This ensures refunds are properly tracked in your budget without manual intervention.

## CSV Format

If using CSV files, the application expects Amazon order CSV files with the following columns:
- Order ID
- Order Date
- Title
- Price
- Quantity

Note: CSV files do not automatically include Subscribe & Save orders. Use email parsing for complete coverage.

## Configuration Options

### YNAB Settings
- `api_key`: Your YNAB API key (required)
- `budget_id`: Your YNAB budget ID (required, found in the URL when viewing your budget)
- `base_url`: YNAB API base URL (default: "https://api.ynab.com/v1")

### Amazon Settings
- `email`: Your email address for Amazon order notifications (optional, enables automatic fetching)
- `email_password`: App-specific password for your email (required if using email)
- `forward_from_address`: Optional - Email address that forwards Subscribe & Save emails to your inbox (if S&S emails are forwarded from another account)
- `csv_file_path`: Path to Amazon orders CSV file (optional, used as fallback)

Note: At least one Amazon data source (email or CSV) must be configured.

### Application Settings
- `processed_transactions_file`: File to track processed transactions (default: "processed_transactions.json")
- `log_level`: Logging level - INFO, DEBUG, WARN, ERROR (default: "INFO")
- `dry_run`: Set to `true` to preview changes without updating YNAB (default: true)
- `look_back_days`: Number of days to look back for transactions (default: 30)

---

### Example `config.yml`

```yaml
ynab:
  api_key: "YOUR_ACTUAL_YNAB_API_KEY"
  budget_id: "YOUR_ACTUAL_YNAB_BUDGET_ID"
  base_url: "https://api.ynab.com/v1"

amazon:
  # Email parsing (automatically includes Subscribe & Save)
  email: "your_amazon_email@example.com"
  email_password: "your_app_password"
  
  # Optional: If Subscribe & Save emails are forwarded from another account
  # forward_from_address: "some.email@example.com"  # Email address that forwards S&S emails to your inbox
  
  # CSV fallback (optional)
  csv_file_path: "amazon_orders.csv"

app:
  processed_transactions_file: "processed_transactions.json"
  log_level: "INFO"
  dry_run: true
  look_back_days: 30
```

> **Important Notes:**
> - The application will first attempt to fetch Amazon orders from your email (if configured). This automatically includes Subscribe & Save orders.
> - If email fetching fails or is not configured, it will use the CSV file as a fallback.
> - For Gmail, you must use an [App Password](https://support.google.com/accounts/answer/185833) (not your main password) and enable IMAP access.
> - Subscribe & Save orders are only available via email parsing, not CSV export.

## Matching Algorithm

The application uses a weighted scoring system to match transactions:

- **Amount Matching (40%)**: Compares transaction amounts with order totals
- **Date Matching (30%)**: Checks if dates are within 7 days of each other
- **Payee Matching (20%)**: Looks for Amazon-related payee names
- **Memo Matching (10%)**: Checks for Amazon references in existing memos

Confidence thresholds:
- High confidence (≥80%): Automatically updated
- Medium confidence (60-79%): Updated with warning
- Low confidence (<60%): Skipped

## Safety Features

- **Dry Run Mode**: Test the application without making changes
- **Transaction Tracking**: Prevents duplicate processing
- **Confidence Thresholds**: Only updates high-confidence matches
- **Error Handling**: Graceful handling of API errors and invalid data

## Troubleshooting

### Common Issues

1. **"YNAB API key not configured"**
   - Make sure you've updated the `api_key` in `config.yml`

2. **"YNAB Budget ID not configured"**
   - Verify your budget ID in the YNAB URL and update `config.yml`

3. **"Neither Amazon email credentials nor CSV file path are configured"**
   - Configure at least one Amazon data source in `config.yml`
   - Email parsing is recommended for automatic Subscribe & Save support

4. **Email authentication fails**
   - Ensure you're using an app-specific password, not your main email password
   - Verify IMAP is enabled in your email account settings
   - For Gmail: [App Passwords Guide](https://support.google.com/accounts/answer/185833)

5. **No Subscribe & Save orders found**
   - Subscribe & Save orders are only available via email parsing
   - Check that your email contains "review your upcoming delivery" messages from Amazon
   - Verify the lookback period covers your subscription deliveries

6. **Refunds not being detected**
   - Refunds are only available via email parsing
   - Check that your email contains "Your refund for" messages from `return@amazon.com`
   - Verify the lookback period covers your refund dates

7. **No matches found**
   - Verify your data source (email or CSV) has orders in the lookback period
   - Check that the dates and amounts match your YNAB transactions
   - Try running in dry-run mode with DEBUG logging to see matching details

8. **API Rate Limits**
   - YNAB has rate limits; the application includes delays between requests
   - If you get rate limit errors, wait a few minutes and try again

### Debug Mode

To see more detailed logging, set the log level to DEBUG in `config.yml`:

```yaml
app:
  log_level: "DEBUG"
```

### Testing

1. Start with `dry_run: true` to see what would be updated
2. Check the logs for matching details
3. Verify the proposed memos look correct
4. Set `dry_run: false` when ready to make actual changes

## File Structure

```
YNABAmazonTransactionUpdater/
├── build.gradle                          # Gradle build configuration
├── config.yml                            # Application configuration
├── config.example.yml                    # Configuration template
├── README.md                             # This file
├── SUBSCRIBE_AND_SAVE.md                 # Subscribe & Save documentation
├── src/
│   ├── main/
│   │   ├── groovy/com/ynab/amazon/
│   │   │   ├── YNABAmazonTransactionUpdater.groovy  # Main application
│   │   │   ├── config/
│   │   │   │   └── Configuration.groovy             # Configuration loader
│   │   │   ├── model/
│   │   │   │   ├── YNABTransaction.groovy          # YNAB transaction model
│   │   │   │   ├── AmazonOrder.groovy              # Amazon order model
│   │   │   │   └── TransactionMatch.groovy         # Match result model
│   │   │   └── service/
│   │   │       ├── YNABService.groovy              # YNAB API service
│   │   │       ├── AmazonService.groovy            # Amazon data orchestration
│   │   │       ├── AmazonOrderFetcher.groovy       # Email parsing service
│   │   │       ├── TransactionMatcher.groovy       # Matching logic
│   │   │       └── TransactionProcessor.groovy     # Processing logic
│   │   └── resources/
│   │       └── logback.xml                         # Logging configuration
│   └── test/
│       ├── groovy/com/ynab/amazon/                 # Unit and integration tests
│       └── resources/                              # Test email samples
└── .agents/scripts/                                # Build and test scripts
```

## Contributing

Feel free to submit issues and enhancement requests!

## License

This project is open source and available under the MIT License. 