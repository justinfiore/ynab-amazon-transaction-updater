package com.ynab.amazon.config

import org.yaml.snakeyaml.Yaml
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Configuration class for loading and validating application settings
 */
class Configuration {
    private static final Logger logger = LoggerFactory.getLogger(Configuration.class)
    
    String ynabApiKey
    String ynabAccountId
    String ynabBudgetId
    String ynabBaseUrl = "https://api.ynab.com/v1"
    String amazonEmail
    String amazonEmailPassword
    String amazonForwardFromAddress
    String amazonCsvFilePath
    String processedTransactionsFile
    String logLevel = "INFO"
    boolean dryRun = false
    int lookBackDays = 30
    
    // Walmart Configuration
    String walmartEmail
    String walmartPassword
    boolean walmartEnabled = false
    boolean walmartHeadless = true
    int walmartBrowserTimeout = 30000
    String walmartOrdersUrl = "https://www.walmart.com/orders"
    int walmartBotDetectionHoldTimeMs = 15000  // Default 15 seconds
    
    Configuration() {
        // Do not auto-load from file by default to keep tests deterministic.
        // Call loadFromFile() explicitly in application entrypoints when needed.
    }
    
    public void loadConfiguration() {
        try {
            def yaml = new Yaml()
            def configFile = new File("config.yml")
            
            if (!configFile.exists()) {
                // Keep defaults when no config file
                logger.warn("Configuration file config.yml not found. Using defaults for optional settings.")
                return
            }
            
            def config = yaml.load(configFile.text)
            
            if (!config.ynab?.api_key) {
                throw new IllegalStateException("Missing required configuration: ynab.api_key")
            }
            
            if (!config.ynab?.budget_id) {
                throw new IllegalStateException("Missing required configuration: ynab.budget_id")
            }
            
            // YNAB Configuration
            this.ynabApiKey = config.ynab.api_key
            this.ynabBudgetId = config.ynab.budget_id
            this.ynabBaseUrl = config.ynab.base_url ?: this.ynabBaseUrl
            
            // Amazon Configuration
            this.amazonEmail = config.amazon.email
            this.amazonEmailPassword = config.amazon.email_password
            this.amazonForwardFromAddress = config.amazon.forward_from_address
            this.amazonCsvFilePath = config.amazon.csv_file_path
            
            // Application Configuration
            this.processedTransactionsFile = config.app.processed_transactions_file
            this.logLevel = config.app.log_level ? config.app.log_level.toUpperCase() : this.logLevel
            this.dryRun = (config.app.dry_run != null) ? config.app.dry_run : this.dryRun
            this.lookBackDays = (config.app.look_back_days != null) ? config.app.look_back_days : this.lookBackDays
            
            // Walmart Configuration
            this.walmartEnabled = (config.walmart?.enabled != null) ? config.walmart.enabled : this.walmartEnabled
            this.walmartEmail = config.walmart?.email
            this.walmartPassword = config.walmart?.password
            this.walmartHeadless = (config.walmart?.headless != null) ? config.walmart.headless : this.walmartHeadless
            this.walmartBrowserTimeout = (config.walmart?.browser_timeout != null) ? config.walmart.browser_timeout : this.walmartBrowserTimeout
            this.walmartOrdersUrl = config.walmart?.orders_url ?: this.walmartOrdersUrl
            this.walmartBotDetectionHoldTimeMs = (config.walmart?.bot_detection_hold_time_ms != null) ? config.walmart.bot_detection_hold_time_ms : this.walmartBotDetectionHoldTimeMs
            
            // Configure SimpleLogger - must be set before any logger instances are created
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", this.logLevel)
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
            System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS")
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
            System.setProperty("org.slf4j.simpleLogger.showLogName", "false")
            System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true")
            
            // Re-initialize the logger to pick up the new settings
            def loggerContext = org.slf4j.LoggerFactory.getILoggerFactory()
            if (loggerContext instanceof ch.qos.logback.classic.LoggerContext) {
                // If using Logback
                def context = (ch.qos.logback.classic.LoggerContext) loggerContext 
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.toLevel(this.logLevel))
                System.out.println("Set log level to ${context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()}")
            }
            
            logger.info("Configuration loaded successfully with log level: ${this.logLevel}")
            
        } catch (Exception e) {
            System.err.println("Error loading configuration: ${e.message}")
            e.printStackTrace()
        }
    }
    
    boolean isValid() {
        if (!ynabApiKey || ynabApiKey == "YOUR_YNAB_API_KEY_HERE") {
            logger.error("YNAB API key not configured")
            return false
        }
        
        if (!ynabBudgetId || ynabBudgetId == "YOUR_YNAB_BUDGET_ID_HERE") {
            logger.error("YNAB Budget ID not configured")
            return false
        }
        
        // Check if either email credentials or CSV file path is configured
        boolean hasEmailConfig = amazonEmail && amazonEmailPassword
        boolean hasCsvConfig = amazonCsvFilePath
        
        if (!hasEmailConfig && !hasCsvConfig) {
            logger.error("Neither Amazon email credentials nor CSV file path are configured")
            return false
        }
        
        // Validate Walmart configuration if enabled
        if (walmartEnabled) {
            if (!walmartEmail) {
                logger.error("Walmart is enabled but walmartEmail is not configured")
                return false
            }
            if (!walmartPassword) {
                logger.error("Walmart is enabled but walmartPassword is not configured")
                return false
            }
        }
        
        return true
    }
    
    boolean isDryRun() {
        return dryRun
    }
}