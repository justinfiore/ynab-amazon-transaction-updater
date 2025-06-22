# Quick Start Guide

## Prerequisites
- Java 11 or higher installed
- YNAB API key
- YNAB Account ID
- Amazon order history CSV export

## 5-Minute Setup

### 1. Install Java (if not already installed)
- **Windows**: Download from [Adoptium](https://adoptium.net/)
- **macOS**: `brew install openjdk@11`
- **Linux**: `sudo apt install openjdk-11-jdk`

### 2. Get Your YNAB Credentials
1. Go to [YNAB Developer Settings](https://app.youneedabudget.com/settings/developer)
2. Create a new API token
3. Copy your YNAB Account ID from the URL when viewing your account

### 3. Export Amazon Orders
**Note: Amazon's "Download order reports" feature is very difficult to find and may not be available in all accounts. Use one of the alternatives below.**

**Option A: Try to Find Amazon's Export (Rarely Available)**
1. Go to Amazon.com and sign in
2. Navigate to "Your Account" → "Your Orders"
3. Look for "Download order reports" or "Order history reports" (usually hidden or not available)
4. If found, select your desired date range and download as CSV

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
3. Save as `amazon_orders.csv` in this project directory

**Option D: Use the Sample File (For Testing)**
1. The project includes `sample_amazon_orders.csv` for testing
2. Copy it to `amazon_orders.csv` to test the application

### 4. Configure the Application
1. Copy `config.example.yml` to `config.yml`
2. Edit `config.yml` with your actual values:
   ```yaml
   ynab:
     api_key: "your_actual_api_key"
     account_id: "your_actual_account_id"
   amazon:
     csv_file_path: "your_amazon_orders.csv"
   app:
     dry_run: true  # Start with true for testing
   ```

### 5. Test and Run
```bash
# Windows
.\gradlew.bat build
.\gradlew.bat run

# Unix/Linux/macOS
./gradlew build
./gradlew run
```

## What Happens Next

1. **First Run**: The app will show what transactions it would update (dry-run mode)
2. **Review**: Check the logs to see proposed changes
3. **Enable**: Set `dry_run: false` in config.yml
4. **Run Again**: The app will actually update your YNAB transactions

## Expected Output

```
Starting YNAB Amazon Transaction Updater
Configuration loaded successfully
Fetching transactions from YNAB...
Found 150 YNAB transactions
Fetching Amazon orders...
Found 25 Amazon orders
Finding unprocessed transactions...
Found 12 unprocessed transactions
Matching transactions with Amazon orders...
Found 8 potential matches
DRY RUN MODE - No changes will be made
Would update transaction: abc123 with memo: Wireless Bluetooth Headphones
Would update transaction: def456 with memo: 3 items: Kindle Paperwhite, USB-C Cable, Phone Case...
YNAB Amazon Transaction Updater completed successfully
```

## Troubleshooting

- **"Java not found"**: Install Java 11+ and add to PATH
- **"Config error"**: Check your `config.yml` format
- **"No matches"**: Verify your CSV file format and dates
- **"API errors"**: Check your YNAB API key and account ID
- **"Can't find Amazon export"**: Try the manual CSV creation option above

## Need Help?

- See `SETUP.md` for detailed instructions
- Run `groovy test_setup.groovy` to diagnose issues
- Check the logs for specific error messages 