package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.TransactionMatch
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
            return true
        }
        
        // Check if memo already contains product information
        if (transaction.memo && (transaction.memo.contains("items:") || 
                                transaction.memo.contains("Amazon Order") ||
                                transaction.memo.length() > 50)) {
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
     * Log transaction details with consistent formatting
     * @param match The transaction match to log
     * @param action The action being performed (e.g., "Updating", "Would update", "Skipping")
     */
    private void logTransactionDetails(TransactionMatch match, String action) {
        def ynabTxn = match.ynabTransaction
        def order = match.amazonOrder
        
        if (logger.isDebugEnabled()) {
            String confidence = match.isHighConfidence() ? "High" : 
                              match.isMediumConfidence() ? "Medium" : "Low"
            
            logger.debug("""
                |${action} Transaction:
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
                |  Order ID: ${order?.orderId ?: 'N/A'}""".stripMargin())
        } else if (!action.toLowerCase().contains("skip")) {
            // Only log non-skipped transactions in info mode
            logger.info("${action} transaction: ${ynabTxn.id} - ${ynabTxn.payee_name} (${ynabTxn.getDisplayAmount()}) - ${match.proposedMemo}")
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
            File file = new File(config.processedTransactionsFile)
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