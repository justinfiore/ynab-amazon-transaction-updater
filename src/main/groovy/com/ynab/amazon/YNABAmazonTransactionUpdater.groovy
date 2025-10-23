package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.service.YNABService
import com.ynab.amazon.service.AmazonService
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
            TransactionMatcher matcher = (TransactionMatcher) services[2]
            TransactionProcessor processor = (TransactionProcessor) services[3]
            
            // Execute the main workflow
            executeWorkflow(ynabService, amazonService, matcher, processor, config)
            
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
     * [YNABService, AmazonService, TransactionMatcher, TransactionProcessor]
     */
    private static List<Object> initializeServices(Configuration config) {
        YNABService ynabService = new YNABService(config)
        AmazonService amazonService = new AmazonService(config)
        TransactionMatcher matcher = new TransactionMatcher()
        TransactionProcessor processor = new TransactionProcessor(config)
        return [ynabService, amazonService, matcher, processor]
    }
    
    private static void executeWorkflow(YNABService ynabService, 
                                      AmazonService amazonService, 
                                      TransactionMatcher matcher, 
                                      TransactionProcessor processor,
                                      Configuration config) {
        
        logger.info("Fetching YNAB transactions...")
        def ynabTransactions = ynabService.getTransactions() ?: []
        logger.info("Found ${ynabTransactions.size()} YNAB transactions")
        
        logger.info("Fetching Amazon orders...")
        def amazonOrders = amazonService.getOrders() ?: []
        logger.info("Found ${amazonOrders.size()} Amazon orders")
        
        logger.info("Finding unprocessed transactions...")
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions) ?: []
        logger.info("Found ${unprocessedTransactions.size()} unprocessed transactions")
        
        logger.info("Matching transactions with Amazon orders...")
        def matches = matcher.findMatches(unprocessedTransactions, amazonOrders) ?: []
        logger.info("Found ${matches.size()} potential matches")
        
        // Process the matches (either update or dry run)
        def stats = processor.updateTransactions(matches, ynabService, config.isDryRun()) ?: [
            updated: 0,
            high_confidence: 0,
            medium_confidence: 0,
            low_confidence: 0
        ]
        
        if (config.isDryRun()) {
            logger.info("Dry run complete. Would update ${stats.updated} transactions (${stats.high_confidence} high, ${stats.medium_confidence} medium confidence)")
        } else {
            logger.info("Successfully updated ${stats.updated} transactions (${stats.high_confidence} high, ${stats.medium_confidence} medium confidence)")
        }
    }
} 