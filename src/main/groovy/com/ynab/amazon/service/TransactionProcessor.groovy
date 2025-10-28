package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.model.WalmartOrder
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service class for processing transactions and tracking processed ones
 */
class TransactionProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class)
    
    private final Configuration config
    private final ObjectMapper objectMapper
    private Set<String> processedTransactionIds
    
    TransactionProcessor(Configuration config) {
        this.config = config
        this.objectMapper = new ObjectMapper()
        this.processedTransactionIds = loadProcessedTransactions()
    }
    
    /**
     * Get transactions that haven't been processed yet
     */
    List<YNABTransaction> getUnprocessedTransactions(List<YNABTransaction> allTransactions) {
        return allTransactions.findAll { transaction ->
            !isProcessed(transaction)
        }
    }
    
    /**
     * Check if a transaction has already been processed
     */
    private boolean isProcessed(YNABTransaction transaction) {
        // Check if transaction ID is in our processed list
        if (processedTransactionIds.contains(transaction.id)) {
            if (logger.isDebugEnabled()) {
                logger.debug("""
                    |Skipping transaction - Found in processed transactions file
                    |  ID: ${transaction.id}
                    |  Date: ${transaction.date}
                    |  Payee: ${transaction.payee_name}
                    |  Amount: ${transaction.getDisplayAmount()}
                    |  Memo: ${transaction.memo}""".stripMargin())
            }
            return true
        }
        
        // Check if memo already contains product information
        if (transaction.memo && (transaction.memo.contains("items:") || 
                                transaction.memo.contains("Amazon Order") ||
                                transaction.memo.contains("Walmart Order") ||
                                transaction.memo.length() > 50)) {
            if (logger.isDebugEnabled()) {
                logger.debug("""
                    |Skipping transaction - Already processed (memo contains product info)
                    |  ID: ${transaction.id}
                    |  Date: ${transaction.date}
                    |  Payee: ${transaction.payee_name}
                    |  Amount: ${transaction.getDisplayAmount()}
                    |  Memo: ${transaction.memo}""".stripMargin())
            }
            return true
        }
        
        return false
    }
    
    /**
     * Update YNAB transactions with new memos or perform a dry run
     * @param matches List of transaction matches to process
     * @param ynabService YNAB service for making API calls
     * @param dryRun If true, only log what would be done without making changes
     * @return Map containing update statistics
     */
    Map<String, Object> updateTransactions(List<TransactionMatch> matches, YNABService ynabService, boolean dryRun = false) {
        int updatedCount = 0
        int highConfidenceCount = matches.count { it.isHighConfidence() }
        int mediumConfidenceCount = matches.count { it.isMediumConfidence() }
        int lowConfidenceCount = matches.count { it.isLowConfidence() }

        if (dryRun) {
            logger.info("DRY RUN MODE - No changes will be made")
            logger.info("Found ${highConfidenceCount} high confidence matches")
        } else {
            logger.info("Updating YNAB transactions...")
        }

        matches.each { match ->
            try {
                def ynabTxn = match.ynabTransaction
                def order = match.amazonOrder
                
                if (match.isHighConfidence()) {
                    logTransactionDetails(match, dryRun ? "Would update" : "Updating")
                    
                    if (!dryRun) {
                        boolean success = ynabService.updateTransactionMemo(
                            ynabTxn.id, 
                            match.proposedMemo
                        )
                        
                        if (success) {
                            markAsProcessed(ynabTxn.id)
                            updatedCount++
                            logger.info("Successfully updated transaction ${ynabTxn.id}")
                        } else {
                            logger.error("Failed to update transaction ${ynabTxn.id}")
                        }
                    } else {
                        updatedCount++
                    }
                } else {
                    logTransactionDetails(match, "Skipping low or medium confidence match")
                }
                
            } catch (Exception e) {
                logger.error("Error processing transaction ${match.ynabTransaction.id}", e)
            }
        }
        
        if (!dryRun) {
            saveProcessedTransactions()
        }
        
        return [
            updated: updatedCount,
            high_confidence: highConfidenceCount,
            medium_confidence: mediumConfidenceCount,
            low_confidence: lowConfidenceCount
        ]
    }
    
    /**
     * Process Walmart matches and update YNAB transactions
     * @param matches List of Walmart transaction matches to process
     * @param ynabService YNAB service for making API calls
     * @param dryRun If true, only log what would be done without making changes
     * @return Map containing update statistics
     */
    Map<String, Object> processWalmartMatches(List<TransactionMatch> matches, YNABService ynabService, boolean dryRun = false) {
        int updatedCount = 0
        int highConfidenceCount = matches.count { it.isHighConfidence() }
        int mediumConfidenceCount = matches.count { it.isMediumConfidence() }
        int lowConfidenceCount = matches.count { it.isLowConfidence() }

        if (dryRun) {
            logger.info("DRY RUN MODE - No changes will be made to Walmart transactions")
            logger.info("Found ${highConfidenceCount} high confidence Walmart matches")
        } else {
            logger.info("Updating YNAB transactions with Walmart order information...")
        }

        matches.each { match ->
            try {
                if (match.isHighConfidence()) {
                    if (match.isMultiTransaction) {
                        // Handle multi-transaction matches
                        updatedCount += processMultiTransactionMatch(match, ynabService, dryRun)
                    } else {
                        // Handle single transaction matches
                        updatedCount += processSingleTransactionMatch(match, ynabService, dryRun)
                    }
                } else {
                    logTransactionDetails(match, "Skipping low or medium confidence Walmart match")
                }
                
            } catch (Exception e) {
                logger.error("Error processing Walmart transaction ${match.ynabTransaction.id}", e)
            }
        }
        
        if (!dryRun) {
            saveProcessedTransactions()
        }
        
        return [
            updated: updatedCount,
            high_confidence: highConfidenceCount,
            medium_confidence: mediumConfidenceCount,
            low_confidence: lowConfidenceCount
        ]
    }
    
    /**
     * Process a single transaction match
     */
    private int processSingleTransactionMatch(TransactionMatch match, YNABService ynabService, boolean dryRun) {
        def ynabTxn = match.ynabTransaction
        def order = match.walmartOrder
        
        String memo = generateWalmartMemo(ynabTxn, order, false, 1, 1)
        
        logTransactionDetails(match, dryRun ? "Would update" : "Updating")
        
        if (!dryRun) {
            boolean success = ynabService.updateTransactionMemo(ynabTxn.id, memo)
            
            if (success) {
                markAsProcessed(ynabTxn.id)
                logger.info("Successfully updated Walmart transaction ${ynabTxn.id}")
                return 1
            } else {
                logger.error("Failed to update Walmart transaction ${ynabTxn.id}")
                return 0
            }
        } else {
            return 1
        }
    }
    
    /**
     * Process a multi-transaction match
     */
    private int processMultiTransactionMatch(TransactionMatch match, YNABService ynabService, boolean dryRun) {
        def order = match.walmartOrder
        def transactions = match.transactions
        int updatedCount = 0
        int totalCharges = transactions.size()
        
        transactions.eachWithIndex { ynabTxn, index ->
            int chargeNumber = index + 1
            String memo = generateWalmartMemo(ynabTxn, order, true, chargeNumber, totalCharges)
            
            // Create a temporary match for logging
            def tempMatch = new TransactionMatch(ynabTxn, order, memo, match.confidenceScore, match.matchReason)
            logTransactionDetails(tempMatch, dryRun ? "Would update" : "Updating")
            
            if (!dryRun) {
                boolean success = ynabService.updateTransactionMemo(ynabTxn.id, memo)
                
                if (success) {
                    markAsProcessed(ynabTxn.id)
                    updatedCount++
                    logger.info("Successfully updated Walmart transaction ${ynabTxn.id} (charge ${chargeNumber} of ${totalCharges})")
                } else {
                    logger.error("Failed to update Walmart transaction ${ynabTxn.id}")
                }
            } else {
                updatedCount++
            }
        }
        
        return updatedCount
    }
    
    /**
     * Generate memo for Walmart transaction
     * @param transaction The YNAB transaction
     * @param order The Walmart order
     * @param isMultiTransaction Whether this is part of a multi-transaction order
     * @param chargeNumber The charge number (for multi-transaction orders)
     * @param totalCharges The total number of charges (for multi-transaction orders)
     * @return The formatted memo string
     */
    private String generateWalmartMemo(YNABTransaction transaction, WalmartOrder order, 
                                       boolean isMultiTransaction, int chargeNumber, int totalCharges) {
        String existingMemo = transaction.memo ?: ""
        String productSummary = order.getProductSummary()
        String orderNumber = order.orderId
        String orderUrl = order.orderUrl ?: order.getOrderLink()
        
        // Build the Walmart order information with URL
        String walmartInfo
        if (isMultiTransaction) {
            walmartInfo = "Walmart Order: ${orderNumber} (Charge ${chargeNumber} of ${totalCharges}) - ${productSummary} - ${orderUrl}"
        } else {
            walmartInfo = "Walmart Order: ${orderNumber} - ${productSummary} - ${orderUrl}"
        }
        
        // Preserve existing memo content
        if (existingMemo && !existingMemo.trim().isEmpty()) {
            return "${existingMemo} | ${walmartInfo}"
        } else {
            return walmartInfo
        }
    }
    
    /**
     * Log transaction details with consistent formatting
     * @param match The transaction match to log
     * @param action The action being performed (e.g., "Updating", "Would update", "Skipping")
     */
    private void logTransactionDetails(TransactionMatch match, String action) {
        def ynabTxn = match.ynabTransaction
        def order = match.amazonOrder ?: match.walmartOrder
        String retailer = match.amazonOrder ? "Amazon" : "Walmart"
        
        if (logger.isDebugEnabled()) {
            String confidence = match.isHighConfidence() ? "High" : 
                              match.isMediumConfidence() ? "Medium" : "Low"
            
            logger.debug("""
                |${action} Transaction:
                |  Retailer: ${retailer}
                |  YNAB ID: ${ynabTxn.id}
                |  Payee: ${ynabTxn.payee_name}
                |  Current Memo: ${ynabTxn.memo}
                |  YNAB Amount: ${ynabTxn.getDisplayAmount()}
                |  Order Total: ${order?.totalAmount ?: 'N/A'}
                |  YNAB Date: ${ynabTxn.date}
                |  Order Date: ${order?.orderDate ?: 'N/A'}
                |  New Memo: ${match.proposedMemo}
                |  Confidence: ${String.format('%.2f', match.confidenceScore * 100)}% (${confidence})
                |  Match Reason: ${match.matchReason}
                |  Order ID: ${order?.orderId ?: 'N/A'}
                |  Multi-Transaction: ${match.isMultiTransaction}""".stripMargin())
        } else if (!action.toLowerCase().contains("skip")) {
            // Only log non-skipped transactions in info mode
            logger.info("${action} ${retailer} transaction: ${ynabTxn.id} - ${ynabTxn.payee_name} (${ynabTxn.getDisplayAmount()}) - ${match.proposedMemo}")
        }
    }
    
    /**
     * Mark a transaction as processed
     */
    private void markAsProcessed(String transactionId) {
        processedTransactionIds.add(transactionId)
    }
    
    /**
     * Load list of previously processed transaction IDs
     */
    private Set<String> loadProcessedTransactions() {
        try {
            String path = config?.processedTransactionsFile
            if (path == null || path.trim().isEmpty()) {
                logger.info("No processed transactions file configured, starting fresh")
                return new HashSet<>()
            }
            File file = new File(path)
            if (!file.exists()) {
                logger.info("No processed transactions file found, starting fresh")
                return new HashSet<>()
            }
            
            def data = objectMapper.readValue(file.text, Map.class)
            def ids = data.get("processed_transaction_ids") as List<String>
            
            logger.info("Loaded ${ids.size()} previously processed transaction IDs")
            return new HashSet<>(ids ?: [])
            
        } catch (Exception e) {
            logger.warn("Error loading processed transactions, starting fresh", e)
            return new HashSet<>()
        }
    }
    
    /**
     * Save list of processed transaction IDs
     */
    private void saveProcessedTransactions() {
        if (!config.processedTransactionsFile) {
            logger.debug("No processed transactions file configured, skipping save")
            return
        }
        
        try {
            def data = [
                processed_transaction_ids: processedTransactionIds.toList(),
                last_updated: new Date().toString()
            ]
            
            // Configure pretty printer for JSON output
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
            
            // Ensure parent directory exists
            File outputFile = new File(config.processedTransactionsFile)
            outputFile.parentFile?.mkdirs()
            outputFile.text = json
            
            logger.info("Saved ${processedTransactionIds.size()} processed transaction IDs")
            
        } catch (Exception e) {
            logger.error("Error saving processed transactions", e)
        }
    }
    
    /**
     * Get statistics about processed transactions
     */
    Map<String, Object> getStatistics(List<YNABTransaction> allTransactions) {
        def unprocessed = getUnprocessedTransactions(allTransactions)
        
        return [
            total_transactions: allTransactions.size(),
            processed_transactions: allTransactions.size() - unprocessed.size(),
            unprocessed_transactions: unprocessed.size(),
            processed_transaction_ids_count: processedTransactionIds.size()
        ]
    }
    
    /**
     * Clear processed transactions (for testing or reset)
     */
    void clearProcessedTransactions() {
        processedTransactionIds.clear()
        saveProcessedTransactions()
        logger.info("Cleared all processed transaction tracking")
    }
} 