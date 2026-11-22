package com.ynab.amazon.config

import org.yaml.snakeyaml.Yaml
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Configuration class for loading and validating application settings
 */
class Configuration {
    private static final Logger logger = LoggerFactory.getLogger(Configuration.class)
    
    public static final String AMAZON_FETCHER_EMAIL = "email"
    public static final String AMAZON_FETCHER_PYTHON = "python"
    
    String ynabApiKey
    String ynabAccountId
    String ynabBudgetId
    String ynabBaseUrl = "https://api.ynab.com/v1"
    String amazonEmail
    String amazonEmailPassword
    String amazonForwardFromAddress
    String amazonCsvFilePath
    String amazonOrderFetcher = AMAZON_FETCHER_EMAIL
    String amazonPythonExecutable
    String amazonPythonBridgeScript
    String amazonPythonConfigPath
    boolean amazonPythonIncludeHouseholdOrders = true
    int amazonPythonTimeoutSeconds = 300
    int amazonPythonMaxOutputBytes = 10485760
    String imapHost = "imap.gmail.com"
    int imapPort = 993
    String processedTransactionsFile
    String logLevel = "INFO"
    boolean dryRun = false
    int lookBackDays = 30
    String lastValidationError
    
    boolean walmartEnabled = false
    String walmartHelperExecutable
    String walmartSessionFile
    int walmartTimeoutSeconds = 300
    int walmartMaxOutputBytes = 8388608
    int walmartMaxPages = 10
    String walmartEnrichment = 'link_only'
    String walmartDefaultCategoryId
    String walmartJournalFile = 'walmart-journal.json'
    Map<String, String> walmartPaymentAccounts = [:]
    Map walmartCategorization = [memory_limit_bytes: 1000000000L, categories: []]
    
    Configuration() {
        // Do not auto-load from file by default to keep tests deterministic.
        // Call loadFromFile() explicitly in application entrypoints when needed.
    }
    
    public void loadConfiguration() {
        loadConfiguration(new File("config.yml"))
    }

    public void loadConfiguration(File configFile) {
        try {
            def yaml = new Yaml()
            
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
            this.amazonOrderFetcher = config.amazon.order_fetcher ?: this.amazonOrderFetcher
            this.amazonPythonExecutable = resolvePath(config.amazon?.python?.executable)
            this.amazonPythonBridgeScript = resolvePath(config.amazon?.python?.bridge_script)
            this.amazonPythonConfigPath = resolvePath(config.amazon?.python?.config_path)
            this.amazonPythonIncludeHouseholdOrders = (config.amazon?.python?.include_household_orders != null) ? config.amazon.python.include_household_orders : this.amazonPythonIncludeHouseholdOrders
            this.amazonPythonTimeoutSeconds = (config.amazon?.python?.timeout_seconds != null) ? config.amazon.python.timeout_seconds : this.amazonPythonTimeoutSeconds
            this.amazonPythonMaxOutputBytes = (config.amazon?.python?.max_output_bytes != null) ? config.amazon.python.max_output_bytes : this.amazonPythonMaxOutputBytes
            this.imapHost = config.amazon.imap_host ?: this.imapHost
            this.imapPort = (config.amazon.imap_port != null) ? config.amazon.imap_port : this.imapPort
            
            // Application Configuration
            this.processedTransactionsFile = config.app.processed_transactions_file
            this.logLevel = config.app.log_level ? config.app.log_level.toUpperCase() : this.logLevel
            this.dryRun = (config.app.dry_run != null) ? config.app.dry_run : this.dryRun
            this.lookBackDays = (config.app.look_back_days != null) ? config.app.look_back_days : this.lookBackDays
            
            // Walmart Configuration
            this.walmartEnabled = (config.walmart?.enabled != null) ? config.walmart.enabled : this.walmartEnabled
            this.walmartHelperExecutable = resolvePath(config.walmart?.helper_executable) ?: bundledWalmartHelper()
            this.walmartSessionFile = resolvePath(config.walmart?.session_file)
            this.walmartTimeoutSeconds = config.walmart?.timeout_seconds != null ? config.walmart.timeout_seconds : this.walmartTimeoutSeconds
            this.walmartMaxOutputBytes = config.walmart?.max_output_bytes != null ? config.walmart.max_output_bytes : this.walmartMaxOutputBytes
            this.walmartMaxPages = config.walmart?.max_pages != null ? config.walmart.max_pages : this.walmartMaxPages
            this.walmartEnrichment = config.walmart?.enrichment ?: this.walmartEnrichment
            this.walmartDefaultCategoryId = config.walmart?.default_category_id
            this.walmartJournalFile = resolvePath(config.walmart?.journal_file) ?: this.walmartJournalFile
            this.walmartPaymentAccounts = config.walmart?.payment_accounts ?: [:]
            this.walmartCategorization = config.walmart?.categorization ?: this.walmartCategorization
            
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
            // Abort startup rather than accepting partially loaded safety settings.
            throw new IllegalStateException('Unable to load configuration', e)
        }
    }
    
    boolean isValid() {
        lastValidationError = null
        if (!ynabApiKey || ynabApiKey == "YOUR_YNAB_API_KEY_HERE") {
            logger.error("YNAB API key not configured")
            return false
        }
        
        if (!ynabBudgetId || ynabBudgetId == "YOUR_YNAB_BUDGET_ID_HERE") {
            logger.error("YNAB Budget ID not configured")
            return false
        }
        
        if (![AMAZON_FETCHER_EMAIL, AMAZON_FETCHER_PYTHON].contains(amazonOrderFetcher)) {
            return invalidAmazon("Invalid amazon.order_fetcher: ${amazonOrderFetcher}. Must be '${AMAZON_FETCHER_EMAIL}' or '${AMAZON_FETCHER_PYTHON}'")
        }

        // Check if either the selected automatic source or CSV file path is configured.
        boolean hasEmailConfig = amazonEmail && amazonEmailPassword
        boolean hasCsvConfig = amazonCsvFilePath
        boolean hasPythonConfig = amazonPythonExecutable && amazonPythonBridgeScript && amazonPythonConfigPath &&
            amazonPythonTimeoutSeconds > 0 && amazonPythonMaxOutputBytes > 0

        if (amazonOrderFetcher == AMAZON_FETCHER_PYTHON && !hasPythonConfig) {
            if (!amazonPythonExecutable) return invalidAmazon('amazon.python.executable is required for Python fetching')
            if (!amazonPythonBridgeScript) return invalidAmazon('amazon.python.bridge_script is required for Python fetching')
            if (!amazonPythonConfigPath) return invalidAmazon('amazon.python.config_path is required for Python fetching')
            if (amazonPythonTimeoutSeconds <= 0) return invalidAmazon('amazon.python.timeout_seconds must be positive')
            return invalidAmazon('amazon.python.max_output_bytes must be positive')
        }
        
        if (amazonOrderFetcher == AMAZON_FETCHER_EMAIL && !hasEmailConfig && !hasCsvConfig) {
            return invalidAmazon("Neither Amazon email credentials nor CSV file path are configured")
        }
        
        if (walmartEnabled) {
            if (!walmartHelperExecutable || !new File(walmartHelperExecutable).canExecute()) return invalidAmazon('Walmart helper unavailable: install a matching -Pwalmart distribution or configure walmart.helper_executable')
            if (!walmartSessionFile) return invalidAmazon('walmart.session_file is required; import a protected browser capture first')
            if (walmartTimeoutSeconds <= 0 || walmartMaxOutputBytes <= 0 || walmartMaxPages < 1 || walmartMaxPages > 20) return invalidAmazon('Walmart process bounds must be positive; max_pages must be 1–20')
            if (!(walmartEnrichment in ['link_only', 'exception_first'])) return invalidAmazon('walmart.enrichment must be link_only or exception_first')
            if (walmartEnrichment == 'exception_first' && (!walmartDefaultCategoryId || !walmartCategorization.categories)) return invalidAmazon('exception_first requires an existing default and category shortlist')
        }
        
        return true
    }
    
    boolean isDryRun() {
        return dryRun
    }

    private static String bundledWalmartHelper() {
        def location = new File(Configuration.protectionDomain.codeSource.location.toURI())
        if (!location.name.endsWith('.jar')) return null
        def helper = new File(location.parentFile.parentFile, 'bin/walmart-helper' + (System.getProperty('os.name').startsWith('Windows') ? '.exe' : ''))
        return helper.isFile() ? helper.absolutePath : null
    }

    private static String resolvePath(def value) {
        if (!value?.toString()?.trim()) return null
        String path = value.toString().trim()
        if (path == '~' || path.startsWith('~/')) path = System.getProperty('user.home') + path.substring(1)
        return Paths.get(path).toAbsolutePath().normalize().toString()
    }

    private boolean invalidAmazon(String message) {
        lastValidationError = message
        logger.error(message)
        return false
    }
}
