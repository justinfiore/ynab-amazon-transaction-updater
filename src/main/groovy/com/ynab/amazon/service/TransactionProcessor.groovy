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
     * Update YNAB transactions with new memos
     */
    int updateTransactions(List<TransactionMatch> matches, YNABService ynabService) {
        int updatedCount = 0
        
        matches.each { match ->
            try {
                if (match.isHighConfidence() || match.isMediumConfidence()) {
                    logger.info("Updating transaction ${match.ynabTransaction.id} with memo: ${match.proposedMemo}")
                    
                    boolean success = ynabService.updateTransactionMemo(
                        match.ynabTransaction.id, 
                        match.proposedMemo
                    )
                    
                    if (success) {
                        markAsProcessed(match.ynabTransaction.id)
                        updatedCount++
                        logger.info("Successfully updated transaction ${match.ynabTransaction.id}")
                    } else {
                        logger.error("Failed to update transaction ${match.ynabTransaction.id}")
                    }
                } else {
                    logger.warn("Skipping low confidence match for transaction ${match.ynabTransaction.id} (score: ${match.confidenceScore})")
                }
                
            } catch (Exception e) {
                logger.error("Error updating transaction ${match.ynabTransaction.id}", e)
            }
        }
        
        // Save processed transactions
        saveProcessedTransactions()
        
        return updatedCount
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
            
            String json = objectMapper.writeValueAsString(data)
            new File(config.processedTransactionsFile).text = json
            
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