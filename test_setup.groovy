#!/usr/bin/env groovy

/**
 * Simple test script to verify the setup and configuration
 * Run after ./gradlew installDist with:
 * java -cp 'build/install/ynab-budget-assistant/lib/*' groovy.ui.GroovyMain test_setup.groovy
 */

import com.ynab.budgetassistant.config.ConfigSchema

println "=== Budget Assistant for YNAB - Setup Test ==="
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
    def config = ConfigSchema.load(configFile)
    println "   ✓ Configuration parsed successfully"
    
    // Check YNAB settings
    if (config.budget.ynab?.api_key && config.budget.ynab.api_key != "YOUR_YNAB_API_KEY_HERE") {
        println "   ✓ YNAB API key configured"
    } else {
        println "   ⚠ YNAB API key not configured (using placeholder)"
    }
    
    if (config.budget.ynab?.budget_id && config.budget.ynab.budget_id != "YOUR_YNAB_BUDGET_ID_HERE") {
        println "   ✓ YNAB Budget ID configured"
    } else {
        println "   ⚠ YNAB Budget ID not configured (using placeholder)"
    }
    
    // Check Amazon settings
    if (config.retailers.amazon?.csv_file_path) {
        def csvFile = new File(config.retailers.amazon.csv_file_path)
        if (csvFile.exists()) {
            println "   ✓ Amazon CSV file found: ${config.retailers.amazon.csv_file_path}"
        } else {
            println "   ⚠ Amazon CSV file not found: ${config.retailers.amazon.csv_file_path}"
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
    "src/main/groovy/com/ynab/budgetassistant/BudgetAssistant.groovy",
    "src/main/groovy/com/ynab/budgetassistant/config/Configuration.groovy",
    "src/main/groovy/com/ynab/budgetassistant/service/YNABService.groovy",
    "src/main/groovy/com/ynab/budgetassistant/service/AmazonService.groovy",
    "src/main/groovy/com/ynab/budgetassistant/service/AmazonTransactionMatcher.groovy",
    "src/main/groovy/com/ynab/budgetassistant/service/TransactionProcessor.groovy"
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
    
    // Check for the supported Java 25 runtime
    def versionParts = javaVersion.split("\\.")
    def majorVersion = versionParts[0].toInteger()
    if (majorVersion == 25) {
        println "   ✓ Java version is compatible (25)"
    } else {
        println "   ⚠ Unsupported Java version (need 25)"
    }
} catch (Exception e) {
    println "   ✗ Could not determine Java version"
}

println ""
println "=== Setup Test Complete ==="
println ""
println "Next steps:"
println "1. Update config.yml with your actual YNAB API key and budget ID"
println "2. Place your Amazon orders CSV file in the project directory"
println "3. Run: .\\gradlew.bat build (Windows) or ./gradlew build (Unix)"
println "4. Run: .\\gradlew.bat run (Windows) or ./gradlew run (Unix)"
println ""
println "For detailed setup instructions, see SETUP.md"
