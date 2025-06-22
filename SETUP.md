# Setup Guide for YNAB Amazon Transaction Updater

## Prerequisites

### 1. Install Java 11 or Higher

#### Windows:
1. Download OpenJDK 11 or higher from [Adoptium](https://adoptium.net/)
2. Run the installer and follow the setup wizard
3. Add Java to your PATH environment variable:
   - Open System Properties → Advanced → Environment Variables
   - Add `C:\Program Files\Eclipse Adoptium\jdk-11.x.x-hotspot\bin` to your PATH
   - Replace `x.x` with your actual version number

#### macOS:
```bash
# Using Homebrew
brew install openjdk@11

# Or download from Adoptium
# https://adoptium.net/
```

#### Linux (Ubuntu/Debian):
```bash
sudo apt update
sudo apt install openjdk-11-jdk
```

### 2. Verify Java Installation

Open a terminal/command prompt and run:
```bash
java -version
```

You should see output like:
```
openjdk version "11.0.12" 2021-07-20
OpenJDK Runtime Environment 18.9 (build 11.0.12+7)
OpenJDK 64-Bit Server VM 18.9 (build 11.0.12+7, mixed mode)
```

## Project Setup

### 1. Download Gradle Wrapper (if needed)

If the gradle wrapper files are missing, download them:

#### Windows (PowerShell):
```powershell
# Create wrapper directory
mkdir -p gradle/wrapper

# Download gradle-wrapper.jar
Invoke-WebRequest -Uri "https://github.com/gradle/gradle/raw/v7.6.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle/wrapper/gradle-wrapper.jar"

# Download gradlew script
Invoke-WebRequest -Uri "https://github.com/gradle/gradle/raw/v7.6.0/gradlew" -OutFile "gradlew"

# Download gradlew.bat
Invoke-WebRequest -Uri "https://github.com/gradle/gradle/raw/v7.6.0/gradlew.bat" -OutFile "gradlew.bat"
```

#### Unix/Linux/macOS:
```bash
# Create wrapper directory
mkdir -p gradle/wrapper

# Download gradle-wrapper.jar
curl -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v7.6.0/gradle/wrapper/gradle-wrapper.jar

# Download gradlew script
curl -o gradlew https://github.com/gradle/gradle/raw/v7.6.0/gradlew
chmod +x gradlew
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
FROM openjdk:11-jre-slim

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
   - Download the gradle wrapper files as shown above

3. **"Build failed"**
   - Check that Java 11+ is installed
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