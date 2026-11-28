package com.ynab.budgetassistant

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.budgetassistant.config.Configuration
import com.ynab.budgetassistant.service.YNABService
import com.ynab.budgetassistant.service.AmazonService
import com.ynab.budgetassistant.service.WalmartService
import com.ynab.budgetassistant.service.AmazonTransactionMatcher
import com.ynab.budgetassistant.service.TransactionProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Main application class for updating YNAB transactions with Amazon order details
 */
class BudgetAssistant {
    private static final Logger logger = LoggerFactory.getLogger(BudgetAssistant.class)
    
    static void main(String[] args) {
        try {
            if (args && args[0] == '--amazon-payment-accounts') {
                File file = new File(args.length > 1 ? args[1] : 'config.yml')
                def setupConfig = new Configuration()
                setupConfig.loadConfiguration(file)
                def ynab = new YNABService(setupConfig)
                try { com.ynab.budgetassistant.service.CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(),ynab,
                    new BufferedReader(new InputStreamReader(System.in)),new PrintWriter(System.out,true)) }
                finally { ynab.close() }
                return
            }
            if (args && args[0] in ['--amazon-setup','--amazon-categories']) {
                com.ynab.budgetassistant.service.CategoryOnboarding.run(new File(args.length > 1 ? args[1] : 'config.yml'),args[0] == '--amazon-categories','amazon')
                return
            }
            if (args && args[0] == '--amazon-disconnect') {
                com.ynab.budgetassistant.service.CategoryOnboarding.disconnect(new File(args.length > 1 ? args[1] : 'config.yml'),'amazon')
                return
            }
            if (args && args[0] == '--walmart-disconnect') {
                com.ynab.budgetassistant.service.CategoryOnboarding.disconnect(new File(args.length>1 ? args[1] : 'config.yml'),'walmart')
                return
            }
            if (args && args[0] == '--walmart-compare') {
                if (!(args.length in [2,4]) || (args.length==4 && args[2]!='--provider')) throw new IllegalArgumentException('Usage: --walmart-compare comparison.yml [--provider jev|openai]')
                com.ynab.budgetassistant.service.WalmartProviderComparison.run(new File(args[1]),args.length==4 ? [args[3]] : ['openai','jev'])
                return
            }
            if (args && args[0] in ['--walmart-setup', '--walmart-categories']) {
                com.ynab.budgetassistant.service.CategoryOnboarding.run(new File(args.length>1 ? args[1] : 'config.yml'),args[0]=='--walmart-categories','walmart')
                return
            }
            logger.info("Starting Budget Assistant for YNAB")
            
            // Load configuration
            Configuration config = new Configuration()
            config.loadConfiguration()
            // Validate configuration (throws on invalid)
            try {
                validateConfig(config)
            } catch (IllegalStateException ex) {
                logger.error("Invalid configuration. Please check config.yml", ex)
                System.exit(1)
            }
            
            // Initialize services
            def services = initializeServices(config)
            YNABService ynabService = (YNABService) services[0]
            AmazonService amazonService = (AmazonService) services[1]
            WalmartService walmartService = (WalmartService) services[2]
            AmazonTransactionMatcher matcher = (AmazonTransactionMatcher) services[3]
            TransactionProcessor processor = (TransactionProcessor) services[4]
            
            // Share non-boundary reads only within this workflow.
            ynabService.beginWorkflow()
            try {
                executeWorkflow(ynabService, amazonService, walmartService, matcher, processor, config)
                ynabService.checkRateLimit()
            } finally {
                ynabService.logRequestCounts()
                ynabService.close()
            }
            
            logger.info("Budget Assistant for YNAB completed successfully")
            
        } catch (Exception e) {
            logger.error("Error in main application", e)
            System.exit(1)
        }
    }
    
    /**
     * Validate the application configuration. Throws IllegalStateException when invalid.
     */
    private static void validateConfig(Configuration config) {
        if (config == null) {
            throw new IllegalStateException("Configuration is null")
        }
        if (!config.isValid()) {
            throw new IllegalStateException("Configuration is invalid")
        }
    }

    /**
     * Initialize and return the core services used by the application in a fixed order:
     * [YNABService, AmazonService, WalmartService, AmazonTransactionMatcher, TransactionProcessor]
     */
    private static List<Object> initializeServices(Configuration config) {
        YNABService ynabService = new YNABService(config)
        AmazonService amazonService = new AmazonService(config)
        WalmartService walmartService = config.walmartEnabled ? new WalmartService(config) : null
        AmazonTransactionMatcher matcher = new AmazonTransactionMatcher()
        TransactionProcessor processor = new TransactionProcessor(config)
        return [ynabService, amazonService, walmartService, matcher, processor]
    }
    
    private static void executeWorkflow(YNABService ynabService, 
                                      AmazonService amazonService,
                                      WalmartService walmartService,
                                      AmazonTransactionMatcher matcher,
                                      TransactionProcessor processor,
                                      Configuration config) {
        
        logger.info("Fetching YNAB transactions...")
        def ynabTransactions = ynabService.getTransactions() ?: []
        logger.info("Found ${ynabTransactions.size()} YNAB transactions")
        
        logger.info("Fetching Amazon orders...")
        def amazonOrders = []
        try { amazonOrders = amazonService.getOrders() ?: [] }
        catch (Exception ignored) { logger.warn('Amazon retrieval failed; continuing independent Walmart processing') }
        logger.info("Found ${amazonOrders.size()} Amazon orders")
        
        // Fetch Walmart orders if enabled
        def walmartOrders = []
        if (config.walmartEnabled && walmartService != null) {
            if (ynabService.getWalmartPlanCurrency() == 'USD') {
                logger.info("Fetching Walmart orders...")
                try { walmartOrders = walmartService.getOrders() ?: [] }
                catch (Exception ignored) { logger.warn('Walmart retrieval failed; continuing Amazon processing without Walmart data') }
            } else {
                logger.warn('Walmart requires a verified USD YNAB plan; skipping Walmart without changing Amazon processing')
            }
            logger.info("Found ${walmartOrders.size()} Walmart orders")
        } else {
            logger.info("Walmart integration is disabled, skipping Walmart order fetching")
        }
        
        logger.info("Finding unprocessed transactions...")
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions) ?: []
        logger.info("Found ${unprocessedTransactions.size()} unprocessed transactions")
        
        // Match and process Amazon orders
        logger.info("Matching transactions with Amazon orders...")
        def amazonMatches = matcher.findMatches(unprocessedTransactions, amazonOrders) ?: []
        logger.info("Found ${amazonMatches.size()} potential Amazon matches")
        
        def amazonStats
        try { amazonStats = processor.processAmazonTransactions(ynabTransactions,amazonOrders,ynabService,config.isDryRun()) }
        catch (Exception ignored) { logger.warn('Amazon enrichment stopped safely; continuing independent Walmart processing') }
        amazonStats = amazonStats ?: [
            updated: 0,
            high_confidence: 0,
            medium_confidence: 0,
            low_confidence: 0
        ]
        
        if (config.isDryRun()) {
            def preview = amazonStats.preview ?: []
            def json = new ObjectMapper()
            logger.info('Amazon dry-run amounts in YNAB milliunits (1000 = 1 currency unit)')
            preview.each { logger.info('Amazon dry-run preview: {}',json.writeValueAsString(it)) }
            int proposed = preview.findAll { it.changes }*.transaction_id.unique().size()
            logger.info("Amazon dry run complete. Would update ${proposed} transactions (${amazonStats.high_confidence} high, ${amazonStats.medium_confidence} medium confidence)")
        } else {
            logger.info("Successfully updated ${amazonStats.updated} Amazon transactions (${amazonStats.high_confidence} high, ${amazonStats.medium_confidence} medium confidence)")
        }
        
        if (amazonService.isLoginRequired()) {
            def pendingAmazonTransactions = unprocessedTransactions.findAll { transaction ->
                !transaction.isTransfer() && !transaction.isDeleted() && transaction.amount != null && transaction.amount < 0 &&
                    matcher.isPotentialAmazonTransaction(transaction) &&
                    !(transaction.id in (amazonStats.completed ?: [])) &&
                    !(config.isDryRun() && amazonMatches.any { it.isHighConfidence() && it.ynabTransaction.id == transaction.id })
            }
            processor.addAmazonLoginNotices(pendingAmazonTransactions, ynabService, config.isDryRun())
        }

        if (config.sheetzEnabled) {
            try {
                if (ynabService.getPlanCurrency() != 'USD') throw new IllegalStateException('Sheetz requires a USD plan')
                Set accounts = ynabService.getBudgetAccounts().intersect(config.sheetzAccounts as Set) as Set
                if (!accounts || accounts.size() != config.sheetzAccounts.size()) throw new IllegalStateException('Sheetz accounts are not eligible')
                def feed = com.ynab.budgetassistant.service.SheetzSource.fetch(config)
                if (feed.incomplete) logger.warn('Sheetz receipt matching is incomplete; showing preview only, with no writes or unmatched fallback')
                boolean previewOnly = config.isDryRun() || feed.incomplete
                // Competitors may be six days before the first target: ±3 days on both sides of a receipt.
                def candidates = ynabService.getTransactions(config.lookBackDays + 6)
                def result = com.ynab.budgetassistant.service.SheetzEnrichment.process(candidates, feed.orders, ynabService, accounts,
                    previewOnly, config.sheetzMemoWrites || previewOnly, config.sheetzCategoryWrites || previewOnly,
                    config.sheetzGasolineDefaultCategoryId, config.sheetzUnmatchedDefaultCategoryId, !feed.incomplete,
                    ynabTransactions*.id as Set, config.sheetzCategorization, config.ynabBudgetId, null, config.sheetzAllowApprovedUncategorized)
                result.preview.each { preview ->
                    logger.info('Sheetz preview: transaction={} reason={} fields={}',
                        preview.transaction_id, preview.reason, (preview.changes ?: [:]).keySet())
                }
                result.failures.each { failure ->
                    logger.warn('Sheetz write not confirmed: transaction={} reason={}', failure.transaction_id, failure.reason)
                }
                logger.info('Sheetz updated {} transactions', result.updated)
            } catch (Exception ignored) {
                logger.warn('Sheetz processing stopped safely; check source, session and configuration. Other retailers continue.')
            }
        }

        // Match and process Walmart orders if enabled
        if (config.walmartEnabled && walmartService != null && !walmartOrders.isEmpty()) {
            logger.info("Matching transactions with Walmart orders...")
            def walmartMatches = matcher.findWalmartMatches(ynabTransactions, walmartOrders, config.walmartPaymentAccounts) ?: []
            logger.info("Found ${walmartMatches.size()} potential Walmart matches")
            
            def walmartStats = processor.processWalmartMatches(walmartMatches, ynabService, config.isDryRun()) ?: [
                updated: 0,
                high_confidence: 0,
                medium_confidence: 0,
                low_confidence: 0
            ]
            
            if (config.isDryRun()) {
                logger.info("Walmart dry run complete. Would update ${walmartStats.updated} transactions (${walmartStats.high_confidence} high, ${walmartStats.medium_confidence} medium confidence)")
            } else {
                logger.info("Successfully updated ${walmartStats.updated} Walmart transactions (${walmartStats.high_confidence} high, ${walmartStats.medium_confidence} medium confidence)")
            }
        }
    }
}
