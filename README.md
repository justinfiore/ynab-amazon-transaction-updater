# YNAB Amazon Transaction Updater

A Groovy application that automatically updates YNAB (You Need A Budget) transactions with Amazon order details by matching transactions and updating the memo field with product summaries.

## Features

- Connects to YNAB API to fetch transactions
- Reads Amazon order data from CSV export
- Intelligently matches YNAB transactions with Amazon orders
- Updates transaction memos with product summaries
- Tracks processed transactions to avoid duplicates
- Supports dry-run mode for testing
- Configurable matching confidence thresholds

## Prerequisites

- Java 11 or higher
- Gradle (for building)
- YNAB API key
- YNAB Account ID
- Amazon order history CSV export

## Setup

### 1. Get Your YNAB API Key

1. Go to [YNAB Developer Settings](https://app.youneedabudget.com/settings/developer)
2. Click "New Token"
3. Give it a name (e.g., "Amazon Transaction Updater")
4. Copy the generated API key

### 2. Get Your YNAB Account ID

1. Go to your YNAB budget
2. Navigate to the account you want to process
3. The account ID is in the URL: `https://app.youneedabudget.com/.../accounts/{ACCOUNT_ID}`

### 3. Export Amazon Order History

**Note: Amazon's "Download order reports" feature is very difficult to find and may not be available in all accounts. Use one of the alternatives below.**

**Option A: Try to Find Amazon's Export (Rarely Available)**
1. Go to [Amazon.com](https://www.amazon.com)
2. Sign in to your account
3. Go to "Your Account" → "Your Orders"
4. Look for "Download order reports" or "Order history reports" (usually hidden or not available)
5. If found, select the date range and download as CSV
6. Save the CSV file in your project directory

**Option B: Use the Helper Script (Recommended)**
1. Run `groovy create_amazon_csv.groovy` to interactively create your CSV file
2. Follow the prompts to enter your Amazon order details
3. The script will create `amazon_orders.csv` for you

**Option C: Manual CSV Creation**
1. Go to Amazon.com → Your Account → Your Orders
2. Manually copy order details into a CSV file with these columns:
   ```
   Order ID,Order Date,Title,Price,Quantity
   123-4567890-1234567,2024-01-15,Wireless Bluetooth Headphones,29.99,1
   ```
3. Save as `amazon_orders.csv` in your project directory

**Option D: Use the Sample File (For Testing)**
1. The project includes `sample_amazon_orders.csv` for testing
2. Copy it to `amazon_orders.csv` to test the application

### 4. Configure the Application

Edit `config.yml` and update the following values:

```yaml
ynab:
  api_key: "YOUR_ACTUAL_YNAB_API_KEY"
  account_id: "YOUR_ACTUAL_YNAB_ACCOUNT_ID"
  base_url: "https://api.ynab.com/v1"

amazon:
  email: "your_amazon_email@example.com"  # IMAP email for Amazon order confirmations
  email_password: "your_amazon_email_app_password"  # App password for your email
  csv_file_path: "your_amazon_orders.csv"  # Fallback if email fetching is not used

app:
  processed_transactions_file: "processed_transactions.json"
  log_level: "INFO"
  dry_run: true
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
2. **YNAB Integration**: Fetches transactions from your specified YNAB account
3. **Amazon Data**: Reads order history from the CSV export
4. **Transaction Matching**: Uses intelligent algorithms to match YNAB transactions with Amazon orders based on:
   - Amount similarity (40% weight)
   - Date proximity (30% weight)
   - Payee name matching (20% weight)
   - Memo content (10% weight)
5. **Memo Updates**: Updates matched transactions with product summaries
6. **Tracking**: Maintains a list of processed transactions to avoid duplicates

## CSV Format

The application expects Amazon order CSV files with the following columns:
- Order ID
- Order Date
- Title
- Price
- Quantity

If your CSV has different column names or order, you may need to modify the `AmazonService.groovy` file.

## Configuration Options

### YNAB Settings
- `api_key`: Your YNAB API key
- `account_id`: The YNAB account ID to process
- `base_url`: YNAB API base URL (usually doesn't need to change)

### Amazon Settings
- `email`: Your Amazon email address (IMAP, e.g. Gmail) for automatic order fetching
- `email_password`: App password for your Amazon email (never use your main password; see your email provider's documentation for how to generate an app password)
- `csv_file_path`: Path to your Amazon orders CSV file (used as fallback if email fetching is not configured or fails)

### Application Settings
- `processed_transactions_file`: File to track processed transactions
- `log_level`: Logging level (INFO, DEBUG, WARN, ERROR)
- `dry_run`: Set to `true` to see what would be updated without making changes

---

### Example `config.yml`

```yaml
ynab:
  api_key: "YOUR_ACTUAL_YNAB_API_KEY"
  account_id: "YOUR_ACTUAL_YNAB_ACCOUNT_ID"
  base_url: "https://api.ynab.com/v1"

amazon:
  email: "your_amazon_email@example.com"  # IMAP email for Amazon order confirmations
  email_password: "your_amazon_email_app_password"  # App password for your email
  csv_file_path: "your_amazon_orders.csv"  # Fallback if email fetching is not used

app:
  processed_transactions_file: "processed_transactions.json"
  log_level: "INFO"
  dry_run: true
```

> **Note:** The application will first attempt to fetch Amazon orders from your email (if `email` and `email_password` are provided). If that fails or is not configured, it will use the CSV file as a fallback.
> For Gmail, you must use an [App Password](https://support.google.com/accounts/answer/185833) (not your main password) and enable IMAP access in your account settings.

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

2. **"YNAB Account ID not configured"**
   - Verify your account ID in the YNAB URL and update `config.yml`

3. **"Amazon CSV file not found"**
   - Check that the CSV file path in `config.yml` is correct
   - Ensure the file exists in the specified location

4. **"Can't find Amazon order export"**
   - Amazon's interface changes frequently
   - Try the manual CSV creation option in the setup instructions
   - Use the provided `sample_amazon_orders.csv` for testing

5. **No matches found**
   - Verify your CSV file has the correct format
   - Check that the dates and amounts are in the expected format
   - Try running in dry-run mode to see what transactions are being considered

6. **API Rate Limits**
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
├── build.gradle                 # Gradle build configuration
├── config.yml                   # Application configuration
├── README.md                    # This file
├── src/
│   └── main/
│       └── groovy/
│           └── com/
│               └── ynab/
│                   └── amazon/
│                       ├── YNABAmazonTransactionUpdater.groovy  # Main application
│                       ├── config/
│                       │   └── Configuration.groovy             # Configuration loader
│                       ├── model/
│                       │   ├── YNABTransaction.groovy          # YNAB transaction model
│                       │   ├── AmazonOrder.groovy              # Amazon order model
│                       │   └── TransactionMatch.groovy         # Match result model
│                       └── service/
│                           ├── YNABService.groovy              # YNAB API service
│                           ├── AmazonService.groovy            # Amazon CSV service
│                           ├── TransactionMatcher.groovy       # Matching logic
│                           └── TransactionProcessor.groovy     # Processing logic
```

## Contributing

Feel free to submit issues and enhancement requests!

## License

This project is open source and available under the MIT License. 