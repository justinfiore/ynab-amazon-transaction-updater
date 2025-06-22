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
1. Go to Amazon.com → Your Account → Your Orders
2. Click "Download order reports"
3. Save the CSV file in this project directory

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

## Need Help?

- See `SETUP.md` for detailed instructions
- Run `groovy test_setup.groovy` to diagnose issues
- Check the logs for specific error messages 