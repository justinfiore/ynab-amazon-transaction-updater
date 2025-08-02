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
    String ynabBaseUrl
    String amazonEmail
    String amazonEmailPassword
    String amazonCsvFilePath
    String processedTransactionsFile
    String logLevel
    boolean dryRun
    
    Configuration() {
        loadConfiguration()
    }
    
    private void loadConfiguration() {
        try {
            def yaml = new Yaml()
            def configFile = new File("config.yml")
            
            if (!configFile.exists()) {
                logger.error("Configuration file config.yml not found")
                return
            }
            
            def config = yaml.load(configFile.text)
            
            // YNAB Configuration
            this.ynabApiKey = config.ynab.api_key
            this.ynabAccountId = config.ynab.account_id
            this.ynabBaseUrl = config.ynab.base_url ?: "https://api.ynab.com/v1"
            
            // Amazon Configuration
            this.amazonEmail = config.amazon.email
            this.amazonEmailPassword = config.amazon.email_password
            this.amazonCsvFilePath = config.amazon.csv_file_path
            
            // Application Configuration
            this.processedTransactionsFile = config.app.processed_transactions_file
            this.logLevel = config.app.log_level ? config.app.log_level.toUpperCase() : "INFO"
            this.dryRun = config.app.dry_run ?: false
            
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
        
        if (!ynabAccountId || ynabAccountId == "YOUR_YNAB_ACCOUNT_ID_HERE") {
            logger.error("YNAB Account ID not configured")
            return false
        }
        
        // Check if either email credentials or CSV file path is configured
        boolean hasEmailConfig = amazonEmail && amazonEmailPassword
        boolean hasCsvConfig = amazonCsvFilePath
        
        if (!hasEmailConfig && !hasCsvConfig) {
            logger.error("Neither Amazon email credentials nor CSV file path are configured")
            return false
        }
        
        return true
    }
    
    boolean isDryRun() {
        return dryRun
    }
}