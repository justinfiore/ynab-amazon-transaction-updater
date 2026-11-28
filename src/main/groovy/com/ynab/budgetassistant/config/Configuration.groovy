package com.ynab.budgetassistant.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

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
    String amazonEnrichment = 'memo_only'
    String amazonJournalFile = 'amazon-journal.json'
    Map amazonCategorization = [categories:[]]
    String amazonFingerprintCredentialsFile
    Map<String,String> amazonPaymentAccounts = [:]
    Boolean amazonTransactionEvidenceEnabled
    boolean amazonRefundEmailEvidenceEnabled = false
    String amazonPythonExecutable
    String amazonPythonBridgeScript
    String amazonPythonConfigPath
    boolean amazonPythonIncludeHouseholdOrders = true
    int amazonPythonTimeoutSeconds = 300
    int amazonPythonMaxOutputBytes = 10485760
    String imapHost = "imap.gmail.com"
    int imapPort = 993
    String processedTransactionsFile
    boolean requireExistingProcessedTransactionsFile = false
    String logLevel = "INFO"
    boolean dryRun = false
    int lookBackDays = 30
    String lastValidationError
    
    boolean walmartEnabled = false
    String walmartCurrency = 'USD'
    boolean walmartAllowApprovedUncategorized = false
    String walmartHelperExecutable
    String walmartSessionFile
    int walmartTimeoutSeconds = 300
    int walmartMaxOutputBytes = 8388608
    int walmartMaxPages = 10
    String walmartEnrichment = 'link_only'
    String walmartDefaultCategoryId
    String walmartJournalFile = 'walmart-journal.json'
    Map<String, String> walmartPaymentAccounts = [:]
    Map walmartCategorization = [categories: []]

    boolean sheetzEnabled = false
    boolean sheetzMemoWrites = false
    boolean sheetzCategoryWrites = false
    boolean sheetzAllowApprovedUncategorized = false
    String sheetzPythonExecutable
    String sheetzBridgeScript = 'scripts/sheetz_bridge.py'
    String sheetzCredentialsFile
    String sheetzTokenFile
    String sheetzGasolineDefaultCategoryId
    String sheetzUnmatchedDefaultCategoryId
    List<String> sheetzAccounts = []
    Map sheetzCategorization = [categories: []]
    
    Configuration() {
        // Do not auto-load from file by default to keep tests deterministic.
        // Call loadFromFile() explicitly in application entrypoints when needed.
    }
    
    public void loadConfiguration() {
        loadConfiguration(new File("config.yml"))
    }

    public void loadConfiguration(File configFile) {
        try {
            def config = ConfigSchema.load(configFile)
            
            if (!config.budget.ynab?.api_key) {
                throw new IllegalStateException("Missing required configuration: ynab.api_key")
            }
            
            if (!config.budget.ynab?.budget_id) {
                throw new IllegalStateException("Missing required configuration: ynab.budget_id")
            }
            
            // YNAB Configuration
            this.ynabApiKey = config.budget.ynab.api_key
            this.ynabBudgetId = config.budget.ynab.budget_id
            this.ynabBaseUrl = config.budget.ynab.base_url ?: this.ynabBaseUrl
            
            // Amazon Configuration
            this.amazonEmail = config.retailers.amazon?.email
            this.amazonEmailPassword = config.retailers.amazon?.email_password
            this.amazonForwardFromAddress = config.retailers.amazon?.forward_from_address
            this.amazonCsvFilePath = config.retailers.amazon?.csv_file_path
            this.amazonOrderFetcher = config.retailers.amazon?.order_fetcher ?: this.amazonOrderFetcher
            this.amazonEnrichment = config.retailers.amazon?.enrichment ?: this.amazonEnrichment
            this.amazonJournalFile = resolvePath(config.retailers.amazon?.journal_file) ?: this.amazonJournalFile
            this.amazonCategorization = config.retailers.amazon?.categorization ?: this.amazonCategorization
            this.amazonPaymentAccounts = config.retailers.amazon?.payment_accounts ?: [:]
            def transactionEvidence = config.retailers.amazon?.transaction_evidence?.enabled
            def refundEmail = config.retailers.amazon?.refund_email_evidence?.enabled
            if ((transactionEvidence != null && !(transactionEvidence instanceof Boolean)) ||
                (refundEmail != null && !(refundEmail instanceof Boolean))) throw new IllegalArgumentException('Amazon evidence enablement must be boolean')
            this.amazonTransactionEvidenceEnabled = transactionEvidence
            this.amazonRefundEmailEvidenceEnabled = refundEmail ?: false
            this.amazonPythonExecutable = resolvePath(config.retailers.amazon?.python?.executable)
            this.amazonPythonBridgeScript = resolvePath(config.retailers.amazon?.python?.bridge_script)
            this.amazonPythonConfigPath = resolvePath(config.retailers.amazon?.python?.config_path)
            this.amazonPythonIncludeHouseholdOrders = (config.retailers.amazon?.python?.include_household_orders != null) ? config.retailers.amazon.python.include_household_orders : this.amazonPythonIncludeHouseholdOrders
            this.amazonPythonTimeoutSeconds = (config.retailers.amazon?.python?.timeout_seconds != null) ? config.retailers.amazon.python.timeout_seconds : this.amazonPythonTimeoutSeconds
            this.amazonPythonMaxOutputBytes = (config.retailers.amazon?.python?.max_output_bytes != null) ? config.retailers.amazon.python.max_output_bytes : this.amazonPythonMaxOutputBytes
            this.imapHost = config.retailers.amazon?.imap_host ?: this.imapHost
            this.imapPort = (config.retailers.amazon?.imap_port != null) ? config.retailers.amazon?.imap_port : this.imapPort
            
            // Application Configuration
            this.processedTransactionsFile = config.runtime.processed_transactions_file
            this.requireExistingProcessedTransactionsFile = config.runtime.require_existing_processed_transactions_file == true
            this.amazonFingerprintCredentialsFile = config.runtime.amazon_fingerprint_credentials_file
            this.logLevel = config.runtime.log_level ? config.runtime.log_level.toUpperCase() : this.logLevel
            this.dryRun = (config.runtime.dry_run != null) ? config.runtime.dry_run : this.dryRun
            this.lookBackDays = (config.runtime.look_back_days != null) ? config.runtime.look_back_days : this.lookBackDays
            
            // Walmart Configuration
            this.walmartEnabled = (config.retailers.walmart?.enabled != null) ? config.retailers.walmart.enabled : this.walmartEnabled
            this.walmartCurrency = config.retailers.walmart?.currency != null ? config.retailers.walmart.currency : this.walmartCurrency
            def allowApproved = config.retailers.walmart?.allow_approved_uncategorized
            if (allowApproved != null && !(allowApproved instanceof Boolean)) throw new IllegalArgumentException('walmart.allow_approved_uncategorized must be a boolean')
            this.walmartAllowApprovedUncategorized = allowApproved ?: false
            this.walmartHelperExecutable = resolvePath(config.retailers.walmart?.helper_executable) ?: bundledWalmartHelper()
            this.walmartSessionFile = resolvePath(config.retailers.walmart?.session_file)
            this.walmartTimeoutSeconds = config.retailers.walmart?.timeout_seconds != null ? config.retailers.walmart.timeout_seconds : this.walmartTimeoutSeconds
            this.walmartMaxOutputBytes = config.retailers.walmart?.max_output_bytes != null ? config.retailers.walmart.max_output_bytes : this.walmartMaxOutputBytes
            this.walmartMaxPages = config.retailers.walmart?.max_pages != null ? config.retailers.walmart.max_pages : this.walmartMaxPages
            this.walmartEnrichment = config.retailers.walmart?.enrichment ?: this.walmartEnrichment
            this.walmartDefaultCategoryId = config.retailers.walmart?.default_category_id
            this.walmartJournalFile = resolvePath(config.retailers.walmart?.journal_file) ?: this.walmartJournalFile
            this.walmartPaymentAccounts = config.retailers.walmart?.payment_accounts ?: [:]
            this.walmartCategorization = config.retailers.walmart?.categorization ?: this.walmartCategorization

            ['enabled', 'memo_writes', 'category_writes', 'allow_approved_uncategorized'].each { key ->
                def value = config.retailers.sheetz?.get(key)
                if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("sheetz.${key} must be boolean")
            }
            this.sheetzEnabled = config.retailers.sheetz?.enabled ?: false
            this.sheetzMemoWrites = config.retailers.sheetz?.memo_writes ?: false
            this.sheetzCategoryWrites = config.retailers.sheetz?.category_writes ?: false
            this.sheetzAllowApprovedUncategorized = config.retailers.sheetz?.allow_approved_uncategorized ?: false
            this.sheetzPythonExecutable = resolvePath(config.retailers.sheetz?.python_executable)
            this.sheetzBridgeScript = resolvePath(config.retailers.sheetz?.bridge_script) ?: this.sheetzBridgeScript
            this.sheetzCredentialsFile = resolvePath(config.retailers.sheetz?.credentials_file)
            this.sheetzTokenFile = resolvePath(config.retailers.sheetz?.token_file)
            this.sheetzGasolineDefaultCategoryId = config.retailers.sheetz?.gasoline_default_category_id
            this.sheetzUnmatchedDefaultCategoryId = config.retailers.sheetz?.unmatched_default_category_id
            if (config.retailers.sheetz?.accounts != null && !(config.retailers.sheetz.accounts instanceof List)) throw new IllegalArgumentException('sheetz.accounts must be a list')
            this.sheetzAccounts = config.retailers.sheetz?.accounts ?: []
            this.sheetzCategorization = config.retailers.sheetz?.categorization ?: this.sheetzCategorization
            
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

        if (!(amazonEnrichment in ['memo_only','item_categories'])) return invalidAmazon('amazon.enrichment must be memo_only or item_categories')
        if (amazonEnrichment == 'item_categories' && !amazonCategorization.categories) return invalidAmazon('Amazon item_categories requires its own category shortlist, without a default')
        if (amazonOrderFetcher != AMAZON_FETCHER_PYTHON && isAmazonTransactionEvidenceEnabled() && !hasPythonConfig) return invalidAmazon('Amazon transaction evidence requires Python runtime/session settings')
        if (amazonRefundEmailEvidenceEnabled && !hasEmailConfig) return invalidAmazon('Supplemental refund email requires explicit IMAP credentials')
        if (amazonPaymentAccounts.any { k,v -> !(k ==~ /python:[a-z0-9 -]+:[0-9]{4}/) || !(v ==~ /[A-Za-z0-9_-]{1,100}/) }) return invalidAmazon('Invalid confirmed Amazon payment identity/account mapping')

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
            try { java.util.Currency.getInstance(walmartCurrency) }
            catch (Exception ignored) { return invalidAmazon('walmart.currency must be an uppercase ISO 4217 currency code') }
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

    static Set<String> readRequiredProcessedTransactions(String filename) {
        try {
            def path = Paths.get(filename).toAbsolutePath().normalize()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                path.toRealPath() != path || !Files.isReadable(path) ||
                Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) != Files.getOwner(Paths.get(System.getProperty('user.home'))) ||
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != PosixFilePermissions.fromString('rw-------')) {
                throw new IllegalStateException('Unsafe processed state')
            }
            def data = new ObjectMapper().readValue(Files.readAllBytes(path), Map)
            if (!(data.processed_transaction_ids instanceof List) || data.processed_transaction_ids.any { !(it instanceof String) }) {
                throw new IllegalStateException('Invalid processed state')
            }
            return new HashSet<String>(data.processed_transaction_ids as List<String>)
        } catch (Exception ignored) {
            throw new IllegalStateException('Migrated processed state is missing, unreadable, unsafe or invalid; stage and verify the original state before startup')
        }
    }

    Map getAmazonCategorizationFingerprint() {
        amazonFingerprintCredentialsFile == null ? amazonCategorization :
            amazonCategorization + [credentials_file:amazonFingerprintCredentialsFile]
    }

    boolean isAmazonTransactionEvidenceEnabled() {
        amazonTransactionEvidenceEnabled != null ? amazonTransactionEvidenceEnabled : amazonOrderFetcher == AMAZON_FETCHER_PYTHON
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
