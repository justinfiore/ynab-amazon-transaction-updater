package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.service.YNABService
import com.ynab.amazon.service.AmazonService
import com.ynab.amazon.service.WalmartService
import com.ynab.amazon.service.TransactionMatcher
import com.ynab.amazon.service.TransactionProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Main application class for updating YNAB transactions with Amazon order details
 */
class YNABAmazonTransactionUpdater {
    private static final Logger logger = LoggerFactory.getLogger(YNABAmazonTransactionUpdater.class)
    
    static void main(String[] args) {
        try {
            if (args && args[0] == '--walmart-disconnect') {
                com.ynab.amazon.service.WalmartOnboarding.disconnect(new File(args.length>1 ? args[1] : 'config.yml'))
                return
            }
            if (args && args[0] == '--walmart-compare') {
                if (!(args.length in [2,4]) || (args.length==4 && args[2]!='--provider')) throw new IllegalArgumentException('Usage: --walmart-compare comparison.yml [--provider jev|openai]')
                com.ynab.amazon.service.WalmartProviderComparison.run(new File(args[1]),args.length==4 ? [args[3]] : ['openai','jev'])
                return
            }
            if (args && args[0] in ['--walmart-setup', '--walmart-categories']) {
                com.ynab.amazon.service.WalmartOnboarding.run(new File(args.length>1 ? args[1] : 'config.yml'),args[0]=='--walmart-categories')
                return
            }
            logger.info("Starting YNAB Amazon Transaction Updater")
            
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
            TransactionMatcher matcher = (TransactionMatcher) services[3]
            TransactionProcessor processor = (TransactionProcessor) services[4]
            
            // Execute the main workflow
            executeWorkflow(ynabService, amazonService, walmartService, matcher, processor, config)
            
            logger.info("YNAB Amazon Transaction Updater completed successfully")
            
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
     * [YNABService, AmazonService, WalmartService, TransactionMatcher, TransactionProcessor]
     */
    private static List<Object> initializeServices(Configuration config) {
        YNABService ynabService = new YNABService(config)
        AmazonService amazonService = new AmazonService(config)
        WalmartService walmartService = config.walmartEnabled ? new WalmartService(config) : null
        TransactionMatcher matcher = new TransactionMatcher()
        TransactionProcessor processor = new TransactionProcessor(config)
        return [ynabService, amazonService, walmartService, matcher, processor]
    }
    
    private static void executeWorkflow(YNABService ynabService, 
                                      AmazonService amazonService,
                                      WalmartService walmartService,
                                      TransactionMatcher matcher, 
                                      TransactionProcessor processor,
                                      Configuration config) {
        
        logger.info("Fetching YNAB transactions...")
        def ynabTransactions = ynabService.getTransactions() ?: []
        logger.info("Found ${ynabTransactions.size()} YNAB transactions")
        
        logger.info("Fetching Amazon orders...")
        def amazonOrders = amazonService.getOrders() ?: []
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
        
        def amazonStats = processor.updateTransactions(amazonMatches, ynabService, config.isDryRun()) ?: [
            updated: 0,
            high_confidence: 0,
            medium_confidence: 0,
            low_confidence: 0
        ]
        
        if (config.isDryRun()) {
            logger.info("Amazon dry run complete. Would update ${amazonStats.updated} transactions (${amazonStats.high_confidence} high, ${amazonStats.medium_confidence} medium confidence)")
        } else {
            logger.info("Successfully updated ${amazonStats.updated} Amazon transactions (${amazonStats.high_confidence} high, ${amazonStats.medium_confidence} medium confidence)")
        }
        
        if (amazonService.isLoginRequired()) {
            def pendingAmazonTransactions = unprocessedTransactions.findAll { transaction ->
                !transaction.isTransfer() && !transaction.isDeleted() && transaction.amount != null && transaction.amount < 0 &&
                    matcher.isPotentialAmazonTransaction(transaction) &&
                    !(config.isDryRun() && amazonMatches.any { it.isHighConfidence() && it.ynabTransaction.id == transaction.id })
            }
            processor.addAmazonLoginNotices(pendingAmazonTransactions, ynabService, config.isDryRun())
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
