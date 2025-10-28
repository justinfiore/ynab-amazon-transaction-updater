# YNAB Transaction Updater

A Groovy application that automatically updates YNAB (You Need A Budget) transactions with retailer order details by matching transactions and updating the memo field with product summaries. Supports Amazon and Walmart orders.

## Features

- Connects to YNAB API to fetch transactions
- **Amazon Integration:**
  - Email parsing (automatic order confirmation emails)
  - Amazon Subscribe & Save delivery notifications
  - Amazon refund notifications
  - CSV export files
  - Special handling for Subscribe & Save orders with "S&S:" prefix
  - Automatic refund detection and processing with positive amounts
- **Walmart Integration:**
  - Browser automation to fetch order history
  - Individual charge matching (matches transactions to specific final charge amounts)
  - Automatic filtering of delivered orders only
  - Extracts final charges (ignores temporary holds)
  - Direct links to Walmart order details pages
- Intelligently matches YNAB transactions with retailer orders
- Updates transaction memos with product summaries
- Tracks processed transactions to avoid duplicates
- Supports dry-run mode for testing
- Configurable matching confidence thresholds
- Configurable lookback period for transactions

## Prerequisites

- Java 11 or higher
- Gradle (for building)
- YNAB API key
- YNAB Budget ID
- **For Amazon:** Order history (via email or CSV export)
- **For Walmart:** Walmart account credentials (email and password)
- (Optional) Email credentials for automatic Amazon order fetching

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

### 3. Set Up Retailer Data Sources

#### Amazon Order Data Source

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

#### Walmart Order Data Source (Optional)

The application can automatically fetch Walmart orders using two different modes:

**Mode 1: Guest Mode (Recommended - No Walmart Password Required)**

Requirements:
- Walmart account email address
- Email credentials (Gmail or other IMAP provider) with app password
- Playwright browser automation library (automatically installed)

Note: You can use the same email account as Amazon, or a different one. Each requires its own app password.

How it works:
1. Searches your email for Walmart order notifications (from help@walmart.com or configured forward address)
2. Extracts order IDs and dates from emails
3. For each order, uses Walmart's guest order lookup feature
4. Fills in email and order number to access order details
5. Extracts order information including:
   - Order number and date
   - Final charge amounts (ignores temporary holds)
   - Product summaries
   - Order status

**Mode 2: Login Mode (Full Account Access)**

Requirements:
- Walmart account credentials (email and password)
- Playwright browser automation library (automatically installed)

How it works:
1. Launches a headless browser
2. Logs into your Walmart account
3. Navigates to your order history
4. Fetches delivered orders within the lookback period
5. Extracts order details

**Important Notes:**
- Guest mode is recommended as it doesn't require storing your Walmart password
- Only "Delivered" orders are processed
- Orders with multiple charges are handled by matching each transaction to individual final charge amounts
- The application extracts only "Final Order Charges" from the Charge History, ignoring "Temporary Hold" charges
- Browser automation typically takes 10-15 seconds per fetch

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

walmart:
  enabled: true  # Set to true to enable Walmart integration
  mode: "guest"  # Mode: "guest" (email lookup, no Walmart password) or "login" (requires Walmart password)
  email: "your_walmart_email@example.com"  # Walmart account email (required for both modes)
  email_password: "your_walmart_email_app_password"  # Email app password for IMAP (required for guest mode)
  # password: "your_walmart_password"  # Walmart account password (only needed for login mode)
  # forward_from_address: "some.email@example.com"  # Optional: for forwarded Walmart emails (guest mode only)
  headless: true  # Set to false to see browser in action (default: true)
  browser_timeout: 30000  # Optional: timeout in milliseconds (default: 30000)
  orders_url: "https://www.walmart.com/orders"  # Optional: custom orders URL

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
3. **Retailer Data Fetching**:
   - **Amazon**: Fetches order history from email (automatic parsing of order confirmations and Subscribe & Save notifications) or CSV file (fallback or manual option)
   - **Walmart**: Uses browser automation to fetch order history from walmart.com (if enabled)
4. **Transaction Matching**: Uses intelligent algorithms to match YNAB transactions with retailer orders:
   - **Amazon**: Amount (40%), Date (30%), Payee (20%), Memo (10%)
   - **Walmart**: Amount (70%), Date (20%), Payee (10%) - matches individual transactions to specific final charge amounts
5. **Memo Updates**: Updates matched transactions with product summaries:
   - **Amazon Regular**: "Product Name" or "3 items: Product A, Product B, Product C"
   - **Amazon Subscribe & Save**: "S&S: Product Name" or "S&S: 3 items: Product A, Product B, Product C"
   - **Amazon Refunds**: "REFUND: Product Name" with positive amounts
   - **Walmart**: "[existing memo] | Walmart Order: [order_number] - [product_summary]"
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

## Walmart Individual Charge Matching

Walmart often splits a single order into multiple credit card charges. The application handles this by matching each YNAB transaction to individual final charge amounts:

**How it works:**
1. Fetches Walmart orders and extracts the "Charge History" for each order
2. Identifies only "Final Order Charges" (ignores "Temporary Hold" charges)
3. Matches each YNAB transaction to a specific final charge amount from any order
4. Updates matched transactions with Walmart order information

**Example:**
- Walmart Order #123: Total $150.00, Status: "Delivered"
- Charge History shows:
  - Temporary Hold: $150.00 (IGNORED)
  - Final Charge 1: $100.00 on 2024-01-15
  - Final Charge 2: $50.00 on 2024-01-16
- YNAB Transactions:
  - Transaction A: -$100.00, 2024-01-15, "WALMART.COM"
  - Transaction B: -$50.00, 2024-01-16, "WALMART.COM"
- Result: Both transactions updated with "Walmart Order: 123 - [product_summary]"

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

### Walmart Settings
- `enabled`: Enable/disable Walmart integration (default: false)
- `email`: Your Walmart account email (required if enabled)
- `password`: Your Walmart account password (required if enabled)
- `headless`: Run browser in headless mode (default: true, set to false to see browser in action while testing)
- `browser_timeout`: Browser operation timeout in milliseconds (optional, default: 30000)
- `orders_url`: Walmart orders page URL (optional, default: "https://www.walmart.com/orders")

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

walmart:
  enabled: true  # Enable Walmart integration
  email: "your_walmart_email@example.com"
  password: "your_walmart_password"
  headless: true  # Set to false to see browser in action (default: true)
  browser_timeout: 30000  # Optional
  orders_url: "https://www.walmart.com/orders"  # Optional

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

### Amazon Matching
- **Amount Matching (40%)**: Compares transaction amounts with order totals
- **Date Matching (30%)**: Checks if dates are within 7 days of each other
- **Payee Matching (20%)**: Looks for Amazon-related payee names
- **Memo Matching (10%)**: Checks for Amazon references in existing memos

### Walmart Individual Charge Matching
- **Amount Matching (70%)**: Compares transaction amount with individual final charge amounts
- **Date Matching (20%)**: Checks if dates are within 7 days of each other
- **Payee Matching (10%)**: Looks for Walmart-related payee names

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

8. **Walmart authentication fails**
   - Verify your Walmart email and password are correct in `config.yml`
   - Check that your Walmart account is accessible via web browser
   - Ensure you don't have 2FA enabled (browser automation doesn't support 2FA yet)
   - Try increasing `browser_timeout` if operations are timing out

9. **Walmart browser automation issues**
   - Ensure Playwright is properly installed (should happen automatically with Gradle)
   - Check that you have sufficient disk space for browser downloads
   - Try running with DEBUG logging to see browser automation details
   - Verify your network connection is stable

10. **Walmart transactions not matching**
   - Verify that transaction amounts match individual final charge amounts (not order totals)
   - Check that transaction dates are within 7 days of the order date
   - Ensure transactions have Walmart-related payee names
   - Review the Charge History in your Walmart order to confirm final charge amounts

11. **API Rate Limits**
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
│   │   │   │   ├── WalmartOrder.groovy             # Walmart order model
│   │   │   │   └── TransactionMatch.groovy         # Match result model
│   │   │   └── service/
│   │   │       ├── YNABService.groovy              # YNAB API service
│   │   │       ├── AmazonService.groovy            # Amazon data orchestration
│   │   │       ├── AmazonOrderFetcher.groovy       # Email parsing service
│   │   │       ├── WalmartService.groovy           # Walmart data orchestration
│   │   │       ├── WalmartOrderFetcher.groovy      # Browser automation service
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