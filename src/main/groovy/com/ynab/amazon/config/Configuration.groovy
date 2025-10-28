package com.ynab.amazon.config

import org.yaml.snakeyaml.Yaml
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Configuration class for loading and validating application settings
 */
class Configuration {
    private static final Logger logger = LoggerFactory.getLogger(Configuration.class)
    
    // Walmart Mode Constants
    public static final String WALMART_MODE_GUEST = "guest"
    public static final String WALMART_MODE_LOGIN = "login"
    
    String ynabApiKey
    String ynabAccountId
    String ynabBudgetId
    String ynabBaseUrl = "https://api.ynab.com/v1"
    String amazonEmail
    String amazonEmailPassword
    String amazonForwardFromAddress
    String amazonCsvFilePath
    String imapHost = "imap.gmail.com"
    int imapPort = 993
    String processedTransactionsFile
    String logLevel = "INFO"
    boolean dryRun = false
    int lookBackDays = 30
    
    // Walmart Configuration
    String walmartEmail  // Email address to search for order notifications (IMAP)
    String walmartWalmartEmail  // Actual Walmart account email (used in order lookup form)
    String walmartEmailPassword
    String walmartPassword
    String walmartForwardFromAddress
    String walmartImapHost  // Walmart IMAP host (defaults to amazon.imap_host if not specified)
    int walmartImapPort = 0  // Walmart IMAP port (defaults to amazon.imap_port if not specified, 0 means not set)
    String walmartMode = WALMART_MODE_GUEST  // "guest" or "login"
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
            this.imapHost = config.amazon.imap_host ?: this.imapHost
            this.imapPort = (config.amazon.imap_port != null) ? config.amazon.imap_port : this.imapPort
            
            // Application Configuration
            this.processedTransactionsFile = config.app.processed_transactions_file
            this.logLevel = config.app.log_level ? config.app.log_level.toUpperCase() : this.logLevel
            this.dryRun = (config.app.dry_run != null) ? config.app.dry_run : this.dryRun
            this.lookBackDays = (config.app.look_back_days != null) ? config.app.look_back_days : this.lookBackDays
            
            // Walmart Configuration
            this.walmartEnabled = (config.walmart?.enabled != null) ? config.walmart.enabled : this.walmartEnabled
            this.walmartEmail = config.walmart?.email
            this.walmartWalmartEmail = config.walmart?.walmart_email
            this.walmartEmailPassword = config.walmart?.email_password
            this.walmartPassword = config.walmart?.password
            this.walmartForwardFromAddress = config.walmart?.forward_from_address
            this.walmartImapHost = config.walmart?.imap_host
            this.walmartImapPort = (config.walmart?.imap_port != null) ? config.walmart.imap_port : this.walmartImapPort
            this.walmartMode = config.walmart?.mode ?: this.walmartMode
            this.walmartHeadless = (config.walmart?.headless != null) ? config.walmart.headless : this.walmartHeadless
            this.walmartBrowserTimeout = (config.walmart?.browser_timeout != null) ? config.walmart.browser_timeout : this.walmartBrowserTimeout
            this.walmartOrdersUrl = config.walmart?.orders_url ?: this.walmartOrdersUrl
            this.walmartBotDetectionHoldTimeMs = (config.walmart?.bot_detection_hold_time_ms != null) ? config.walmart.bot_detection_hold_time_ms : this.walmartBotDetectionHoldTimeMs
            
            // Fallback logic: If only one email is specified, use it for both
            if (this.walmartEmail && !this.walmartWalmartEmail) {
                this.walmartWalmartEmail = this.walmartEmail
                logger.debug("walmart.walmart_email not specified, using walmart.email for both IMAP and order lookup")
            } else if (!this.walmartEmail && this.walmartWalmartEmail) {
                this.walmartEmail = this.walmartWalmartEmail
                logger.debug("walmart.email not specified, using walmart.walmart_email for both IMAP and order lookup")
            }
            
            // Fallback logic: If Walmart IMAP not specified, use Amazon IMAP settings
            if (!this.walmartImapHost) {
                this.walmartImapHost = this.imapHost
                logger.debug("walmart.imap_host not specified, using amazon.imap_host: ${this.imapHost}")
            }
            if (this.walmartImapPort == 0) {
                this.walmartImapPort = this.imapPort
                logger.debug("walmart.imap_port not specified, using amazon.imap_port: ${this.imapPort}")
            }
            
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
            
            // Validate mode
            if (walmartMode && ![WALMART_MODE_GUEST, WALMART_MODE_LOGIN].contains(walmartMode)) {
                logger.error("Invalid walmart.mode: ${walmartMode}. Must be '${WALMART_MODE_GUEST}' or '${WALMART_MODE_LOGIN}'")
                return false
            }
            
            // Validate required fields based on mode
            if (walmartMode == WALMART_MODE_GUEST) {
                if (!walmartEmailPassword) {
                    logger.error("Walmart mode is '${WALMART_MODE_GUEST}' but walmartEmailPassword is not configured (needed for IMAP access)")
                    return false
                }
            } else if (walmartMode == WALMART_MODE_LOGIN) {
                if (!walmartPassword) {
                    logger.error("Walmart mode is '${WALMART_MODE_LOGIN}' but walmartPassword is not configured")
                    return false
                }
            }
        }
        
        return true
    }
    
    boolean isDryRun() {
        return dryRun
    }
}