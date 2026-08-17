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