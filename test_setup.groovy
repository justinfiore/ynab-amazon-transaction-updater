#!/usr/bin/env groovy

/**
 * Simple test script to verify the setup and configuration
 * Run with: groovy test_setup.groovy
 */

@Grab('org.yaml:snakeyaml:2.0')
@Grab('org.slf4j:slf4j-simple:2.0.7')

import org.yaml.snakeyaml.Yaml

println "=== YNAB Amazon Transaction Updater - Setup Test ==="
println ""

// Test 1: Check if config.yml exists
println "1. Checking configuration file..."
def configFile = new File("config.yml")
if (configFile.exists()) {
    println "   ✓ config.yml found"
} else {
    println "   ✗ config.yml not found"
    println "   Please create config.yml with your settings"
    System.exit(1)
}

// Test 2: Parse configuration
println "2. Parsing configuration..."
try {
    def yaml = new Yaml()
    def config = yaml.load(configFile.text)
    println "   ✓ Configuration parsed successfully"
    
    // Check YNAB settings
    if (config.ynab?.api_key && config.ynab.api_key != "YOUR_YNAB_API_KEY_HERE") {
        println "   ✓ YNAB API key configured"
    } else {
        println "   ⚠ YNAB API key not configured (using placeholder)"
    }
    
    if (config.ynab?.account_id && config.ynab.account_id != "YOUR_YNAB_ACCOUNT_ID_HERE") {
        println "   ✓ YNAB Account ID configured"
    } else {
        println "   ⚠ YNAB Account ID not configured (using placeholder)"
    }
    
    // Check Amazon settings
    if (config.amazon?.csv_file_path) {
        def csvFile = new File(config.amazon.csv_file_path)
        if (csvFile.exists()) {
            println "   ✓ Amazon CSV file found: ${config.amazon.csv_file_path}"
        } else {
            println "   ⚠ Amazon CSV file not found: ${config.amazon.csv_file_path}"
        }
    } else {
        println "   ⚠ Amazon CSV file path not configured"
    }
    
} catch (Exception e) {
    println "   ✗ Error parsing configuration: ${e.message}"
    System.exit(1)
}

// Test 3: Check source files
println "3. Checking source files..."
def sourceFiles = [
    "src/main/groovy/com/ynab/amazon/YNABAmazonTransactionUpdater.groovy",
    "src/main/groovy/com/ynab/amazon/config/Configuration.groovy",
    "src/main/groovy/com/ynab/amazon/service/YNABService.groovy",
    "src/main/groovy/com/ynab/amazon/service/AmazonService.groovy",
    "src/main/groovy/com/ynab/amazon/service/TransactionMatcher.groovy",
    "src/main/groovy/com/ynab/amazon/service/TransactionProcessor.groovy"
]

def missingFiles = []
sourceFiles.each { filePath ->
    if (new File(filePath).exists()) {
        println "   ✓ ${filePath}"
    } else {
        println "   ✗ ${filePath}"
        missingFiles.add(filePath)
    }
}

if (missingFiles.size() > 0) {
    println "   ⚠ Some source files are missing"
} else {
    println "   ✓ All source files found"
}

// Test 4: Check build files
println "4. Checking build files..."
def buildFiles = [
    "build.gradle",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.properties"
]

buildFiles.each { filePath ->
    if (new File(filePath).exists()) {
        println "   ✓ ${filePath}"
    } else {
        println "   ✗ ${filePath}"
    }
}

// Test 5: Check Java
println "5. Checking Java installation..."
try {
    def javaVersion = System.getProperty("java.version")
    def javaVendor = System.getProperty("java.vendor")
    println "   ✓ Java ${javaVersion} (${javaVendor})"
    
    // Check if Java version is 11 or higher
    def versionParts = javaVersion.split("\\.")
    def majorVersion = versionParts[0].toInteger()
    if (majorVersion >= 11) {
        println "   ✓ Java version is compatible (11+)"
    } else {
        println "   ⚠ Java version may be too old (need 11+)"
    }
} catch (Exception e) {
    println "   ✗ Could not determine Java version"
}

println ""
println "=== Setup Test Complete ==="
println ""
println "Next steps:"
println "1. Update config.yml with your actual YNAB API key and account ID"
println "2. Place your Amazon orders CSV file in the project directory"
println "3. Run: .\\gradlew.bat build (Windows) or ./gradlew build (Unix)"
println "4. Run: .\\gradlew.bat run (Windows) or ./gradlew run (Unix)"
println ""
println "For detailed setup instructions, see SETUP.md" 