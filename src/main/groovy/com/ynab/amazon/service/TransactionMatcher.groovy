package com.ynab.amazon.service

import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.TransactionMatch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Service class for matching YNAB transactions with Amazon orders
 */
class TransactionMatcher {
    private static final Logger logger = LoggerFactory.getLogger(TransactionMatcher.class)
    
    // Amazon-related payee names to look for
    private static final List<String> AMAZON_PAYEE_NAMES = [
        "AMAZON.COM",
        "AMAZON",
        "AMZN",
        "AMAZON.COM*",
        "AMAZON MKTPLACE",
        "AMAZON MARKETPLACE",
        "AMAZON RETAIL"
    ]

    private static final List<String> PAYEE_NAMES_BLACKLIST = [
        "TRANSFER"
    ]
    
    /**
     * Find matches between YNAB transactions and Amazon orders
     */
    List<TransactionMatch> findMatches(List<YNABTransaction> transactions, List<AmazonOrder> orders) {
        List<TransactionMatch> matches = []
        List<YNABTransaction> ynabTransactionsToMatch = transactions.findAll { !isAlreadyProcessed(it) && isPotentialAmazonTransaction(it) }
        logger.info("Found ${ynabTransactionsToMatch.size()} transactions to match")
        ynabTransactionsToMatch.each { transaction ->
            
            // Find best matching Amazon order
            TransactionMatch bestMatch = findBestMatch(transaction, orders)
            if (bestMatch) {
                matches.add(bestMatch)
            }
        }
        
        logger.info("Found ${matches.size()} transaction matches")
        return matches
    }
    
    /**
     * Check if transaction is already processed (has a memo with product details)
     */
    private boolean isAlreadyProcessed(YNABTransaction transaction) {
        if (!transaction.memo) {
            return false
        }
        
        // Check if memo contains product information (indicating it was already processed)
        return transaction.memo.contains("items:") || 
               transaction.memo.contains("Amazon Order") ||
               transaction.memo.length() > 50  // Long memos likely already processed
    }
    
    /**
     * Check if transaction could be an Amazon transaction
     */
    private boolean isPotentialAmazonTransaction(YNABTransaction transaction) {
        // Check payee name
        if (transaction.payee_name) {
            String payee = transaction.payee_name.toUpperCase().trim()
            if (AMAZON_PAYEE_NAMES.any { payee.contains(it) } && !PAYEE_NAMES_BLACKLIST.any { payee.contains(it) }) {
                return true
            }
        }
        
        // Check memo for Amazon references
        if (transaction.memo) {
            String memo = transaction.memo.toUpperCase()
            if (memo.contains("AMAZON") || memo.contains("AMZN")) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Find the best matching Amazon order for a transaction
     */
    private TransactionMatch findBestMatch(YNABTransaction transaction, List<AmazonOrder> orders) {
        TransactionMatch bestMatch = null
        double bestScore = 0.0
        
        orders.each { order ->
            double score = calculateMatchScore(transaction, order)
            if (score > bestScore && score >= 0.5) {  // Minimum confidence threshold
                bestScore = score
                bestMatch = createMatch(transaction, order, score)
            }
        }
        
        return bestMatch
    }
    
    /**
     * Calculate a confidence score for matching a transaction with an order
     */
    private double calculateMatchScore(YNABTransaction transaction, AmazonOrder order) {
        double score = 0.0
        
        // Amount matching (40% weight)
        if (transaction.getAmountInDollars() && order.totalAmount) {
            double amountDiff = Math.abs(transaction.getAmountInDollars() - order.totalAmount)
            double amountScore = 1.0 - (amountDiff / Math.max(Math.abs(transaction.getAmountInDollars()), Math.abs(order.totalAmount)))
            score += amountScore * 0.4
        }
        
        // Date matching (40% weight)
        if (transaction.date && order.orderDate) {
            int daysDiff = calculateDaysDifference(transaction.date, order.orderDate)
            double dateScore = Math.max(0.0, 1.0 - (daysDiff / 7.0))  // Within 7 days
            score += dateScore * 0.4
        }
        
        // Payee name matching (20% weight)
        if (transaction.payee_name && isAmazonPayee(transaction.payee_name)) {
            score += 0.2
        }
        
        return Math.min(1.0, score)
    }
    
    /**
     * Calculate days difference between two date strings (YYYY-MM-DD format)
     */
    private int calculateDaysDifference(String date1, String date2) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
            sdf.setLenient(false)
            
            Date d1 = sdf.parse(date1)
            Date d2 = sdf.parse(date2)
            
            long diffInMillies = Math.abs(d1.getTime() - d2.getTime())
            return (int) (diffInMillies / (1000 * 60 * 60 * 24))
        } catch (Exception e) {
            logger.warn("Could not parse dates: ${date1}, ${date2}: ${e.message}")
            return 999  // Large number to indicate no match
        }
    }
    
    /**
     * Check if payee name is Amazon-related
     */
    private boolean isAmazonPayee(String payeeName) {
        if (!payeeName) return false
        
        String payee = payeeName.toUpperCase().trim()
        return AMAZON_PAYEE_NAMES.any { payee.contains(it) }
    }
    
    /**
     * Create a TransactionMatch object
     */
    private TransactionMatch createMatch(YNABTransaction transaction, AmazonOrder order, double score) {
        String proposedMemo = generateProposedMemo(transaction, order)
        String matchReason = generateMatchReason(transaction, order, score)
        
        return new TransactionMatch(transaction, order, proposedMemo, score, matchReason)
    }
    
    /**
     * Generate a proposed memo for the transaction
     */
    private String generateProposedMemo(YNABTransaction transaction, AmazonOrder order) {
        if (!order.items || order.items.isEmpty()) {
            return "Amazon Order"
        }
        
        if (order.items.size() == 1) {
            return order.items[0].title.split(",")[0]
        }
        
        // For multiple items, create a summary
        String summary = "${order.items.size()} items: "
        summary += order.items.take(3).collect { it.title.split(",")[0] }.join(", ")
        
        if (order.items.size() > 3) {
            summary += " ..."
        }
        String proposedMemo = summary
        if(transaction.memo != null && !transaction.memo.isEmpty() && !transaction.memo.equals("null")) {
            proposedMemo = "${transaction.memo}| ${summary}"
        }
        return proposedMemo
    }
    
    /**
     * Generate a reason for the match
     */
    private String generateMatchReason(YNABTransaction transaction, AmazonOrder order, double score) {
        List<String> reasons = []
        
        if (transaction.amount && order.totalAmount) {
            double amountDiff = Math.abs(transaction.getAmountInDollars() - order.totalAmount)
            if (amountDiff < 0.01) {
                reasons.add("exact amount match")
            } else if (amountDiff < 1.0) {
                reasons.add("close amount match")
            }
        }
        
        if (transaction.date && order.orderDate) {
            int daysDiff = calculateDaysDifference(transaction.date, order.orderDate)
            if (daysDiff == 0) {
                reasons.add("same date")
            } else if (daysDiff <= 3) {
                reasons.add("close date match")
            }
        }
        
        if (isAmazonPayee(transaction.payee_name)) {
            reasons.add("Amazon payee")
        }
        
        return reasons.join(", ")
    }
} 