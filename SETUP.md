# Setup Guide for YNAB Transaction Updater

## Prerequisites

### 1. Install Java 25

#### Windows:
1. Download JDK 25 from [Adoptium](https://adoptium.net/)
2. Run the installer and follow the setup wizard
3. Add Java to your PATH environment variable:
   - Open System Properties → Advanced → Environment Variables
   - Add `C:\Program Files\Eclipse Adoptium\jdk-25.x.x-hotspot\bin` to your PATH
   - Replace `x.x` with your actual version number

#### macOS:
```bash
# Using Homebrew
brew install openjdk@25

# Or download from Adoptium
# https://adoptium.net/
```

#### Linux (Ubuntu/Debian):
```bash
# Install a JDK 25 distribution, such as Adoptium Temurin:
# https://adoptium.net/
```

### 2. Verify Java Installation

Open a terminal/command prompt and run:
```bash
java -version
```

You should see output like:
```
openjdk version "25"
OpenJDK Runtime Environment (...)
OpenJDK 64-Bit Server VM (...)
```

## Project Setup

### 1. Use the Checked-in Gradle Wrapper

The repository includes a complete Gradle 9.6.1 wrapper. Do not install Gradle
separately or download wrapper files by hand. Verify it with:

```bash
./gradlew --version
```

### 2. Configure the Application

1. Edit `config.yml` and update with your actual values:
   ```yaml
   ynab:
     api_key: "YOUR_ACTUAL_YNAB_API_KEY"
     account_id: "YOUR_ACTUAL_YNAB_ACCOUNT_ID"
     base_url: "https://api.ynab.com/v1"

   amazon:
     csv_file_path: "your_amazon_orders.csv"

   walmart:
     enabled: false  # Set to true to enable Walmart integration
     email: "your_walmart_email@example.com"
     password: "your_walmart_password"

   app:
     dry_run: true  # Set to false when ready
   ```

2. Place your Amazon orders CSV file in the project directory

### Optional: Python Amazon Order History

Python mode requires Python 3.9+ on Windows, macOS, or Linux, but is not needed
for email or CSV mode. Create an isolated environment and install the tested pin:

```bash
python3 -m venv .venv-amazon-orders
.venv-amazon-orders/bin/python -m pip install -r requirements-amazon-orders.txt
.venv-amazon-orders/bin/amazon-orders --config-path ~/.config/amazonorders/config.yml login
.venv-amazon-orders/bin/python scripts/amazon_orders_bridge.py --config-path ~/.config/amazonorders/config.yml --preflight
```

Use the equivalent `Scripts\\python.exe` paths on Windows. Configure
`amazon.order_fetcher: "python"` and all `amazon.python` paths from
`config.example.yml`; set a positive timeout and output bound. The upstream
config/cookies should be owner-readable only. Its supported `AMAZON_USERNAME`,
`AMAZON_PASSWORD`, and optional `AMAZON_OTP_SECRET_KEY` environment variables
may be inherited, but must never be written to YAML or logs. Browser challenges
can require `amazon-orders[browser]` and `playwright install chromium`.

`amazon-orders` is an unofficial website parser supporting English pages across Amazon regions and may fail
when Amazon changes. A failed Python fetch returns no Python orders; refresh the
session, increase the timeout only when needed, or roll back by selecting email
or CSV. Python history does not reproduce email-specific refund or Subscribe &
Save notification dates.

#### Amazon authentication: storage, lifetime, and recovery

The stock `amazon-orders login` command above signs in interactively and persists
cookies outside this repository. The bridge calls the library's `login()` on each
fetch, reusing the persisted session or attempting authentication as needed.
`--preflight` only verifies imports/schema; it does **not** check whether you are
signed in or can retrieve orders.

With `amazon-orders==6.0.0`, the defaults are:

- Configuration: `~/.config/amazonorders/config.yml`.
- Default-account, amazon.com session: `~/.config/amazonorders/cookies.json`.
- Other Amazon sites have separate cookie files beside that jar; named accounts
  use `~/.config/amazonorders/accounts/<account>/` (for example,
  `accounts/spouse/cookies.json` for amazon.com).
- An explicit upstream `cookie_jar_path` overrides the cookie location. Changing
  only `amazon.python.config_path` does **not** relocate the default cookie jar.

Protect both config and cookie files with owner-only access. Cookies grant access
to your account: do not commit them, put them in issue reports, or store them in
this project's YAML. For unattended runs in Amp, the optional credential secrets
are `AMAZON_USERNAME`, `AMAZON_PASSWORD`, and `AMAZON_OTP_SECRET_KEY` (the reusable
TOTP seed, not a short-lived OTP code). These can help automatic login, but cannot
guarantee that CAPTCHA, browser, or other interactive challenges will succeed.
A fresh orb also needs its own secure session provisioning or interactive login;
setting credentials does not copy another machine's cookie jar.

There is **no guaranteed session lifetime**. Individual cookies have expiry times,
and Amazon can reject a session earlier or require a new challenge. Do not assume
that a cookie file's existence or age means it is still valid.

If the library detects a redirect to sign-in, needs interactive input, or returns
a recognized credential form with an error box on a successful HTTP response,
the bridge exits with reserved code 3. A generic upstream sign-in exception alone
is insufficient: unknown pages and HTTP failures remain ordinary failures, not
evidence that login is required. The updater logs a fixed failure category
without upstream diagnostic payloads, imports no Python orders, and continues
with configured CSV orders and the rest of the workflow rather than crashing.
Other failures (network, parser, missing dependencies, timeout, output limits)
also remain contained, but do **not** claim that login is required.

For a recognized authentication failure, unprocessed Amazon expenses that were
not successfully augmented receive this exact memo notice:

```text
[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]
```

An existing memo is preserved and the notice appended once with ` | `, for example:

```text
Personal purchase | [ERROR: Amazon Login Required on ynab-amazon-transaction-updater]
```

Already-processed transactions, transfers, deleted transactions, refunds, other
retailers, and successful CSV augmentations are excluded. Dry runs only log what
would happen; they never write the notice. A failed notice write is logged and
does not stop other transactions. The notice does not mark a transaction as
processed: later runs retry it, and successful augmentation removes the notice
while retaining the original memo.

To recover, run the stock login command interactively using the **same upstream
configuration, Amazon site, account, and cookie location as the bridge**:

```bash copy Copy Amazon Login
.venv-amazon-orders/bin/amazon-orders --config-path ~/.config/amazonorders/config.yml login
```

If using a named account, select that same account in the upstream configuration
or its supported environment settings for both commands. Complete any requested
challenges, then run the updater again (optionally dry-run first). If the session
cannot be restored, choose email/CSV mode rather than repeatedly raising timeouts.

### 3. Build and Run

#### Windows:
```cmd
# Build the project
.\gradlew.bat build

# Run the application
.\gradlew.bat run

# Or run the JAR directly
java -jar build/libs/YNABAmazonTransactionUpdater-1.0.0.jar
```

#### Unix/Linux/macOS:
```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Or run the JAR directly
java -jar build/libs/YNABAmazonTransactionUpdater-1.0.0.jar
```

## Alternative: Using Docker

If you prefer to use Docker, create a `Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY build/libs/YNABAmazonTransactionUpdater-1.0.0.jar app.jar
COPY config.yml .
COPY your_amazon_orders.csv .

CMD ["java", "-jar", "app.jar"]
```

Then build and run:
```bash
# Build the application first
./gradlew build

# Build Docker image
docker build -t ynab-amazon-updater .

# Run with Docker
docker run -v $(pwd):/app ynab-amazon-updater
```

## Troubleshooting

### Common Issues:

1. **"JAVA_HOME is not set"**
   - Install Java and add it to your PATH
   - Or set JAVA_HOME environment variable

2. **"Gradle wrapper not found"**
   - Restore the checked-in wrapper files from Git

3. **"Build failed"**
   - Check that Java 25 is installed
   - Verify all source files are in the correct directory structure

4. **"YNAB API key not configured"**
   - Update the `api_key` in `config.yml`

5. **"Amazon CSV file not found"**
   - Check the file path in `config.yml`
    - Ensure the CSV file exists in the specified location

6. **Python bridge errors**
    - Run the documented `--preflight` command to verify interpreter/package/schema.
    - Re-authenticate with the stock `amazon-orders ... login` command if the session is stale.
    - Do not paste stderr containing account data into issue reports.

### Getting Help

If you encounter issues:
1. Check the logs for error messages
2. Verify your configuration in `config.yml`
3. Test with `dry_run: true` first
4. Check that your CSV file format matches the expected structure

## Next Steps

1. Start with `dry_run: true` to test the application
2. Review the proposed changes in the logs
3. Set `dry_run: false` when ready to make actual updates
4. Monitor the processed transactions file to track what's been updated
