# YNAB Transaction Updater

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/justinfiore/ynab-amazon-transaction-updater)

A Groovy application that updates YNAB (You Need A Budget) transactions with retailer order details. Supports Amazon orders and an optional native Go-backed Walmart ledger source. Groovy owns Walmart processing and optional paid OpenAI Decisions/TypeSafe Jev inference. Walmart remains disabled by default; live provider quality and the native platform matrix are not qualified.

## Features

- Connects to YNAB API to fetch transactions
- **Amazon Integration:**
  - Email parsing (automatic order confirmation emails)
  - Amazon Subscribe & Save delivery notifications
  - Amazon refund notifications
   - CSV export files
   - Optional Python order-history fetcher using `amazon-orders==6.0.0`
  - Special handling for Subscribe & Save orders with "S&S:" prefix
  - Automatic refund detection and processing with positive amounts
  - Optional no-default item categorization, exact purchase/refund splits and guarded uncategorized fallback; see [Amazon setup and safety](AMAZON.md)
- **Walmart (optional):** Exact final-charge matching, metadata-only direct links and explicit exception-first allocation using existing categories. See [setup, safety and qualification limits](WALMART.md); no model profile is qualified yet.
- Intelligently matches YNAB transactions with retailer orders
- Updates transaction memos with product summaries
- Tracks processed transactions to avoid duplicates
- Supports dry-run mode for testing
- Configurable matching confidence thresholds
- Configurable lookback period for transactions

## Prerequisites

- Java 25 (the supported build and runtime baseline)
- No system Gradle installation is required; the checked-in wrapper uses Gradle 9.6.1
- YNAB API key
- YNAB Budget ID
- **For Amazon:** Order history (via email, optional Python history, or CSV export)
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

You have three options for providing Amazon order data:

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

**Option C: Python order history (optional)**

A maintained, patched CPython >=3.11.5 can fetch account history through the unofficial `amazon-orders`
website-parsing package. It supports English pages across Amazon regions and can
break when Amazon changes its site. By default, the bridge includes all Amazon
Household orders visible to the signed-in account. Set
`amazon.python.include_household_orders: false` to fetch only that account's own
orders. This option affects Python fetching only, not email or CSV. It is deliberately opt-in: email remains
the default and retains its refund and Subscribe & Save notification semantics.

```bash
python3 -m venv .venv-amazon-orders
.venv-amazon-orders/bin/python -m pip install --require-hashes -r requirements-amazon-orders.txt
.venv-amazon-orders/bin/python -I scripts/amazon_orders_bridge.py --config-path ~/.config/amazonorders/config.yml --login
.venv-amazon-orders/bin/python -I scripts/amazon_orders_bridge.py --config-path ~/.config/amazonorders/config.yml --preflight
```

Use a **fresh virtual environment**, not an existing environment with extra plugins.
The full dependency closure is version/hash-locked and wheel-only; installation
fails where compatible wheels are unavailable. The guarded bridge login persists
its session outside this project. Do not use the stock CLI for this workflow: it
does not enforce the bridge's transport/configuration restrictions. Do not commit its
config/cookies or place passwords, OTP secrets, or cookies in YAML. Supported
Amazon credential environment variables are allowlisted without being printed.
Use restrictive permissions for the upstream configuration. Browser/CAPTCHA
automation, paid solvers, custom Python classes and parser plugins are disabled;
do not install unconstrained browser extras over this lock. If a challenge cannot
be completed using the guarded password/OTP login, use email/CSV until a separate
browser workflow has been reviewed and locked.

For cookie/config locations, session lifetime, Amp credential secrets, and the
interactive recovery command, see [Amazon authentication](SETUP.md#amazon-authentication-storage-lifetime-and-recovery).
When Python authentication requires login, eligible unaugmented Amazon expenses
receive `[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]` in the
memo (appended once, preserving existing text). This is retryable, not a processed
marker: successful augmentation removes it. Dry runs never write it, and generic
fetch failures do not trigger a login notice.

#### Optional Walmart Order Data Source

The removed guest/login browser fetchers have no compatibility fallback. Build with `-Pwalmart` for the reviewed retrieval-only Go helper and import a protected browser capture manually. Disabled/link-only operation requires no inference key. There is no local-model runtime or OAuth route.

See [Walmart setup and safety](WALMART.md) for protected provider-key/category onboarding, synthetic comparison, exact accounting and incomplete quality/platform qualification. Start disabled with dry runs; no paid provider quality is claimed from mock tests.

### 4. Configure the Application

Copy `config.example.yml` to `config.yml` and update the following values:

```yaml
ynab:
  api_key: "YOUR_ACTUAL_YNAB_API_KEY"
  budget_id: "YOUR_ACTUAL_YNAB_BUDGET_ID"
  base_url: "https://api.ynab.com/v1"

amazon:
  order_fetcher: "email" # use "python" for the optional bridge
  # Email parsing (recommended - automatically includes Subscribe & Save)
  email: "your_amazon_email@example.com"
  email_password: "your_app_password"  # Use app-specific password, not main password
  
  # Optional: If Subscribe & Save emails are forwarded from another account
  # forward_from_address: "some.email@example.com"  # Email address that forwards S&S emails to your inbox
  
  # IMAP Configuration (optional, defaults shown)
  # imap_host: "imap.gmail.com"
  # imap_port: 993
  
  # CSV fallback (optional)
  csv_file_path: "amazon_orders.csv"
  # python:
  #   executable: ".venv-amazon-orders/bin/python"
  #   bridge_script: "scripts/amazon_orders_bridge.py"
  #   config_path: "~/.config/amazonorders/config.yml"
  #   include_household_orders: true # false for only the signed-in account's orders
  #   timeout_seconds: 300
  #   max_output_bytes: 10485760

walmart:
  enabled: false  # Fetching unavailable pending Go integration

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

### Run the Tests

```bash
# Unit tests followed by integration tests
.agents/scripts/run-tests.sh
```

The helper copies the generated HTML reports to `test-results/test/index.html`
and `test-results/integrationTest/index.html`.

Python bridge tests need no account or credentials. With the optional dependency
installed, they also exercise the real pinned library's login/history contract
using offline HTTP fixtures (CI runs this configuration):

```bash
.venv-amazon-orders/bin/python -m unittest scripts/test_amazon_orders_bridge.py
```

Running with plain `python3` still runs the isolated fixture tests but skips the
real-library contract test if `amazon-orders` is not installed.

If Python fetching times out, returns a non-zero exit, cannot import the
package, or emits malformed output, the updater logs bounded redacted context
and safely imports no Python orders. Re-run preflight, refresh the persisted
session, or select email/CSV; live Amazon verification is never part of the
default test suite.

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
   - **Walmart**: Optional reviewed Go helper and protected session; disabled by default
4. **Transaction Matching**: Uses intelligent algorithms to match YNAB transactions with retailer orders:
   - **Amazon**: Amount (40%), Date (30%), Payee (20%), Memo (10%)
   - **Walmart**: Exact cents, final-charge dates and global unique assignment with optional payment/account evidence
5. **Memo Updates**: Updates matched transactions with product summaries:
   - **Amazon Regular**: "Product Name" or "3 items: Product A, Product B, Product C"
   - **Amazon Subscribe & Save**: "S&S: Product Name" or "S&S: 3 items: Product A, Product B, Product C"
   - **Amazon Refunds**: "REFUND: Product Name" with positive amounts
   - **Walmart**: "[existing memo] | 2026-11-20 | Total $100.00 | #[order_id] | [direct order URL]"; no generated parent item summary
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

Walmart often splits a single order into multiple charges. The optional Go source retains distinct final-charge occurrences. Global exact matching skips ambiguity and never substitutes order totals. Optional exception-first allocation concentrates exceptions into few transactions; proportional allocation is not supported. See [WALMART.md](WALMART.md).

**Matching:**
1. Obtain normalized final-charge history from the reviewed native helper
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
- Result for a unique assignment: Both transactions linked to Walmart Order #123, each consuming its own charge

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
- `enabled`: `false` by default. Requires a native helper and protected session when enabled. `enrichment` defaults to `link_only`; `exception_first` requires a valid existing shortlist/default. See [all settings and qualification blockers](WALMART.md). Removed browser settings have no compatibility path.

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
  enabled: false  # Fetching unavailable pending Go integration

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
Walmart uses exact integer cents, a 14-day final-charge-date window and Walmart-related purchase payees, with optional account/payment mapping. Globally ambiguous assignments remain unchanged; no fuzzy confidence or order-total fallback applies. Confidence thresholds below refer to Amazon only.

Confidence thresholds:
- High confidence (≥80%): Automatically updated
- Medium confidence (60-79%): Updated with warning
- Low confidence (<60%): Skipped

## Safety Features

- **Dry Run Mode**: Test the application without making changes
- **Transaction Tracking**: Prevents duplicate processing
- **Confidence Thresholds**: Only updates high-confidence matches
- **Error Handling**: Graceful handling of API errors and invalid data
- **Python hardening**: Python mode uses isolated interpreter mode (`-I`) and an environment allowlist: OS runtime/location variables plus `AMAZON_DOMAIN`, `AMAZON_ACCOUNT`, `AMAZON_COOKIE_JAR_PATH`, `AMAZON_USERNAME`, `AMAZON_PASSWORD`, and `AMAZON_OTP_SECRET_KEY`. Unrelated YNAB/email/cloud credentials and Python/remote-browser/proxy overrides are not inherited. The argument-list child has a timeout, bounded output, process-tree cleanup and fixed failure diagnostics; upstream messages and bridge JSON are not logged. Amazon secrets are never command arguments.
- **Amazon network/data boundaries**: The bridge allows only the configured Amazon HTTPS origin, including initial form submissions, direct requests and redirects. TLS verification is mandatory, proxy/CA environment and `.netrc` are ignored, and connect/read timeouts are 10/30 seconds. Custom Python classes, language/parser plugins, CAPTCHA/browser automation and paid solvers are disabled; debug HTML capture stays off. It retrieves calendar-year summaries but only fetches details for dated, non-cancelled, deduplicated orders within the look-back window. Household scope remains configurable and unchanged.
- **Not an OS sandbox**: A virtual environment, `-I`, transport checks and process groups cannot contain malicious installed code. The child still has the OS user's filesystem access, including readable configuration files. For that threat model, use a dedicated unprivileged user/container with only its Amazon session/configuration available, without mounting YNAB/email/cloud secrets. Use a clean environment for manual login, too. Cookies remain plaintext bearer credentials protected by filesystem permissions, not encryption.
- **Linux subprocess cleanup**: Linux runs the Python bridge in a separate process group using `/usr/bin/setsid` (util-linux) and `/bin/kill`. These tools are required on Linux; if unavailable, fetching fails safely rather than launching without group containment. Timeouts and failures terminate the group, including children whose parent has already exited; remaining group members are also cleaned up after normal completion. A helper that deliberately starts a new session/group can escape this boundary. macOS and Windows retain best-effort descendant tracking and daemon output readers; they do not have the Linux group-containment guarantee.
- **Source semantics**: Select one automatic source (`email` or `python`); an optional CSV is appended after it. A failed Python source returns no Python orders rather than silently falling back to another credential source.

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

8. **Walmart helper/session unavailable**
   - Keep disabled or install a matching `-Pwalmart` distribution and import a protected session; see [WALMART.md](WALMART.md)
   - Refresh expired captures manually. The removed browser paths have no fallback; model/platform qualification remains incomplete.

9. **API Rate Limits**
   - YNAB has rate limits; the application includes delays between requests
    - If you get rate limit errors, wait a few minutes and try again

10. **Python bridge preflight or fetch failure**
    - Verify the isolated interpreter and tested dependencies: `.venv-amazon-orders/bin/python -I scripts/amazon_orders_bridge.py --config-path ~/.config/amazonorders/config.yml --preflight`
    - Re-run the same bridge with `--login` instead of `--preflight` if the persisted session is stale. Custom classes/parsers and browser/CAPTCHA flows are intentionally refused; use email/CSV when required.
    - Check that `amazon.python.executable`, `bridge_script`, and `config_path` are absolute/resolved paths and that timeout/output bounds are positive.
    - Do not paste cookies, passwords, OTP values, stderr payloads, or order JSON into logs or issue reports. Select `email` or CSV if an upstream Amazon challenge cannot run unattended.

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
├── requirements-amazon-orders.txt         # Optional pinned Python bridge dependency
├── scripts/amazon_orders_bridge.py        # Schema-v1 Python bridge (run with configured interpreter)
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
│   │   │   ├── service/                            # Email/Python fetchers and bounded runner
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
│   │   │       ├── TransactionMatcher.groovy       # Matching logic
│   │   │       └── TransactionProcessor.groovy     # Processing logic
│   │   └── resources/
│   │       └── logback.xml                         # Logging configuration
│   └── test/
│       ├── groovy/com/ynab/amazon/                 # Unit and integration tests
│       └── resources/                              # Test email samples
└── .agents/scripts/                                # Build and test scripts
```

`./gradlew installDist` packages the bridge and requirement file under the distribution root. Python remains optional for email and CSV users; Java 25 is sufficient for those modes. The bridge uses an unofficial website parser supporting English pages across Amazon regions, so Amazon page changes, authentication challenges, and its history-only model can require recovery or a switch back to email/CSV. Python history does not reproduce email refund-event dates or Subscribe & Save synthetic deliveries.

## Contributing

Feel free to submit issues and enhancement requests!

## License

This project is open source and available under the MIT License. 
