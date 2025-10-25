package com.ynab.amazon.service

import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.WalmartOrder
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
    
    // Walmart-related payee names to look for
    private static final List<String> WALMART_PAYEE_NAMES = [
        "WALMART",
        "WAL-MART",
        "WALMART.COM",
        "WALMART ONLINE"
    ]

    private static final List<String> PAYEE_NAMES_BLACKLIST = [
        "TRANSFER"
    ]
    
    // Do not match transactions with an order if the dates are too far apart
    private static final int MAX_MATCH_DAYS_DIFFERENCE = 14
    
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
        boolean isProcessed = transaction.memo.contains("items:") || 
                           transaction.memo.length() > 100  // Long memos likely already processed
        
        if (isProcessed && logger.isDebugEnabled()) {
            logger.debug("Skipping already processed transaction: {}", transaction.toString())
        }
        
        return isProcessed
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
     * Check if transaction could be a Walmart transaction
     */
    private boolean isPotentialWalmartTransaction(YNABTransaction transaction) {
        // Check payee name
        if (transaction.payee_name) {
            String payee = transaction.payee_name.toUpperCase().trim()
            if (WALMART_PAYEE_NAMES.any { payee.contains(it) } && !PAYEE_NAMES_BLACKLIST.any { payee.contains(it) }) {
                return true
            }
        }
        
        // Check memo for Walmart references
        if (transaction.memo) {
            String memo = transaction.memo.toUpperCase()
            if (memo.contains("WALMART") || memo.contains("WAL-MART")) {
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
        
        // Amount must match exactly for high confidence
        if (transaction.getAmountInDollars() && order.totalAmount) {
            if (Math.abs(transaction.getAmountInDollars() - order.totalAmount) > 0.01) {
                return 0.0  // No match if amounts don't match exactly
            }
            score += 0.7  // Full points for amount match
        } else {
            return 0.0  // No match if amount is missing
        }
        
        // Date matching (20% weight) with special handling for returns
        if (transaction.date && order.orderDate) {
            int daysDiff = calculateDaysDifferenceForMatching(transaction, order)
            // Hard cut-off: if dates are too far apart, do not match at all
            if (daysDiff > MAX_MATCH_DAYS_DIFFERENCE) {
                logger.debug("Date difference ${daysDiff} exceeds max ${MAX_MATCH_DAYS_DIFFERENCE}, rejecting match")
                return 0.0
            }
            double dateScore = Math.max(0.0, 1.0 - (daysDiff / 7.0))  // Within 7 days
            logger.debug("Date score calculation: daysDiff=${daysDiff}, dateScore=${dateScore}")
            score += dateScore * 0.2
        }
        
        // Payee name matching (10% weight)
        if (transaction.payee_name && isAmazonPayee(transaction.payee_name)) {
            score += 0.1
            logger.debug("Added payee score, total now: ${score}")
        }
        
        double finalScore = Math.min(1.0, score)
        logger.debug("Final confidence score: ${finalScore}")
        return finalScore
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
     * Calculate days difference for matching purposes, with special handling for Amazon returns
     */
    private int calculateDaysDifferenceForMatching(YNABTransaction transaction, AmazonOrder order) {
        // For returns where YNAB transaction is later than Amazon order date,
        // allow up to 7 days of "grace period" by adjusting the calculation
        if (order.isReturn && isTransactionDateLater(transaction.date, order.orderDate)) {
            // Calculate the actual difference (transaction date - order date)
            int actualDiff = calculateSignedDaysDifference(transaction.date, order.orderDate)
            
            // If within the return grace period (7 days), treat as same-day for scoring
            if (actualDiff <= 7) {
                return 0  // Perfect date match for confidence scoring
            } else {
                // Beyond grace period, subtract the grace period from the difference
                return actualDiff - 7
            }
        }
        
        // Normal case: use absolute difference
        return calculateDaysDifference(transaction.date, order.orderDate)
    }
    
    /**
     * Check if transaction date is later than order date
     */
    private boolean isTransactionDateLater(String transactionDate, String orderDate) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
            sdf.setLenient(false)
            
            Date txDate = sdf.parse(transactionDate)
            Date ordDate = sdf.parse(orderDate)
            
            return txDate.after(ordDate)
        } catch (Exception e) {
            logger.warn("Could not parse dates for comparison: ${transactionDate}, ${orderDate}: ${e.message}")
            return false
        }
    }
    
    /**
     * Calculate signed days difference (date1 - date2)
     * Positive if date1 is later, negative if date1 is earlier
     */
    private int calculateSignedDaysDifference(String date1, String date2) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
            sdf.setLenient(false)
            
            Date d1 = sdf.parse(date1)
            Date d2 = sdf.parse(date2)
            
            long diffInMillies = d1.getTime() - d2.getTime()
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
     * Check if payee name is Walmart-related
     */
    private boolean isWalmartPayee(String payeeName) {
        if (!payeeName) return false
        
        String payee = payeeName.toUpperCase().trim()
        return WALMART_PAYEE_NAMES.any { payee.contains(it) }
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
     * Generate a sanitized and truncated memo for the transaction
     * @return A memo string that is:
     * - Limited to 500 characters
     * - Contains only a-zA-Z0-9, spaces, hyphens, underscores, and plus signs
     */
    private String generateProposedMemo(YNABTransaction transaction, AmazonOrder order) {
        // Check if this is a Subscribe and Save order
        boolean isSubscribeAndSave = order.orderId?.startsWith("SUB-")
        
        if (!order.items || order.items.isEmpty()) {
            if(order.isReturn) {
                return "Amazon Return (Couldn't identify items)"
            }
            if (isSubscribeAndSave) {
                return "S&S: Amazon Order (Couldn't identify items)"
            }
            return "Amazon Order (Couldn't identify items)"
        }
        
        // Generate the base summary
        String summary = ""
        if (order.items.size() == 1) {
            // For Subscribe and Save, remove the "(Subscribe & Save)" suffix if present
            String itemTitle = order.items[0].title
            if (isSubscribeAndSave && itemTitle.endsWith("(Subscribe & Save)")) {
                itemTitle = itemTitle.replace(" (Subscribe & Save)", "").trim()
            }
            summary = itemTitle.split(",")[0]
        } else {
            // For multiple items, create a summary
            summary = "${order.items.size()} items: "
            summary += order.items.take(3).collect { item ->
                String itemTitle = item.title
                if (isSubscribeAndSave && itemTitle.endsWith("(Subscribe & Save)")) {
                    itemTitle = itemTitle.replace(" (Subscribe & Save)", "").trim()
                }
                itemTitle.split(",")[0]
            }.join(", ")
            
            if (order.items.size() > 3) {
                summary += " ..."
            }
        }
        
        // Add S&S prefix for Subscribe and Save orders
        if (isSubscribeAndSave) {
            summary = "S&S: ${summary}"
        }
        
        // Combine with existing memo if it exists
        String proposedMemo = summary
        if(transaction.memo != null && !transaction.memo.isEmpty() && !transaction.memo.equals("null")) {
            proposedMemo = "${transaction.memo} | ${summary}"
        }
        
        // Sanitize the memo to only allow certain characters
        // Allow comma, pipe, and period to preserve expected formatting
        // Note: + needs to be escaped in the character class
        proposedMemo = proposedMemo.replaceAll(/[^a-zA-Z0-9 _\-\+:'\|\.,&]/, " ")
        
        // Collapse 3+ spaces to exactly 2 spaces to match expected formatting
        proposedMemo = proposedMemo.replaceAll(/ {3,}/, "  ")
        // Enforce length limit
        return proposedMemo.length() > 500 ? proposedMemo.substring(0, 500) : proposedMemo
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
    
    /**
     * Find matches between YNAB transactions and Walmart orders
     */
    List<TransactionMatch> findWalmartMatches(List<YNABTransaction> transactions, List<WalmartOrder> orders) {
        List<TransactionMatch> matches = []
        List<YNABTransaction> ynabTransactionsToMatch = transactions.findAll { 
            !isAlreadyProcessed(it) && isPotentialWalmartTransaction(it) 
        }
        
        logger.info("Found ${ynabTransactionsToMatch.size()} Walmart transactions to match")
        
        // First, try single transaction matches
        List<YNABTransaction> unmatchedTransactions = []
        ynabTransactionsToMatch.each { transaction ->
            TransactionMatch match = findSingleTransactionMatch(transaction, orders)
            if (match) {
                matches.add(match)
            } else {
                unmatchedTransactions.add(transaction)
            }
        }
        
        logger.info("Found ${matches.size()} single-transaction Walmart matches")
        
        // Then, try multi-transaction matches for remaining transactions
        List<WalmartOrder> multiChargeOrders = orders.findAll { it.hasMultipleCharges() }
        if (!unmatchedTransactions.isEmpty() && !multiChargeOrders.isEmpty()) {
            List<TransactionMatch> multiMatches = findMultiTransactionMatches(unmatchedTransactions, multiChargeOrders)
            matches.addAll(multiMatches)
            logger.info("Found ${multiMatches.size()} multi-transaction Walmart matches")
        }
        
        logger.info("Found ${matches.size()} total Walmart transaction matches")
        return matches
    }
    
    /**
     * Find the best matching Walmart order for a single transaction
     */
    private TransactionMatch findSingleTransactionMatch(YNABTransaction transaction, List<WalmartOrder> orders) {
        TransactionMatch bestMatch = null
        double bestScore = 0.0
        
        orders.each { order ->
            double score = calculateWalmartMatchScore(transaction, order)
            if (score > bestScore && score >= 0.5) {  // Minimum confidence threshold
                bestScore = score
                bestMatch = createWalmartMatch(transaction, order, score)
            }
        }
        
        return bestMatch
    }
    
    /**
     * Calculate a confidence score for matching a transaction with a Walmart order
     */
    private double calculateWalmartMatchScore(YNABTransaction transaction, WalmartOrder order) {
        double score = 0.0
        
        // Amount must match exactly for high confidence
        // YNAB stores expenses as negative, so use absolute value
        if (transaction.getAmountInDollars() && order.totalAmount) {
            double transactionAmount = Math.abs(transaction.getAmountInDollars())
            double amountDiff = Math.abs(transactionAmount - order.totalAmount)
            if (amountDiff > 0.01) {
                // For single transaction match, check if it matches any of the final charge amounts
                boolean matchesAnyCharge = false
                if (order.finalChargeAmounts) {
                    matchesAnyCharge = order.finalChargeAmounts.any { charge ->
                        Math.abs(transactionAmount - charge) < 0.01
                    }
                }
                
                if (!matchesAnyCharge) {
                    return 0.0  // No match if amount doesn't match order total or any charge
                }
            }
            score += 0.7  // Full points for amount match
        } else {
            return 0.0  // No match if amount is missing
        }
        
        // Date matching (20% weight)
        if (transaction.date && order.orderDate) {
            int daysDiff = calculateDaysDifference(transaction.date, order.orderDate)
            // Hard cut-off: if dates are too far apart, do not match at all
            if (daysDiff > MAX_MATCH_DAYS_DIFFERENCE) {
                return 0.0
            }
            double dateScore = Math.abs(0.0, 1.0 - (daysDiff / 7.0))  // Within 7 days
            score += dateScore * 0.2
        }
        
        // Payee name matching (10% weight)
        if (transaction.payee_name && isWalmartPayee(transaction.payee_name)) {
            score += 0.1
        }
        
        return Math.min(1.0, score)
    }
    
    /**
     * Create a TransactionMatch object for Walmart
     */
    private TransactionMatch createWalmartMatch(YNABTransaction transaction, WalmartOrder order, double score) {
        String proposedMemo = generateWalmartProposedMemo(transaction, order, false, 1, 1)
        String matchReason = generateWalmartMatchReason(transaction, order, score)
        
        return new TransactionMatch(transaction, order, proposedMemo, score, matchReason)
    }
    
    /**
     * Generate a sanitized and truncated memo for a Walmart transaction
     */
    private String generateWalmartProposedMemo(YNABTransaction transaction, WalmartOrder order, 
                                                boolean isMultiTransaction, int chargeNumber, int totalCharges) {
        String summary = order.getProductSummary()
        
        // Add charge indicator for multi-transaction orders
        if (isMultiTransaction) {
            summary = "Walmart Order: ${order.orderId} (Charge ${chargeNumber} of ${totalCharges}) - ${summary}"
        } else {
            summary = "Walmart Order: ${order.orderId} - ${summary}"
        }
        
        // Combine with existing memo if it exists
        String proposedMemo = summary
        if (transaction.memo != null && !transaction.memo.isEmpty() && !transaction.memo.equals("null")) {
            proposedMemo = "${transaction.memo} | ${summary}"
        }
        
        // Sanitize the memo to only allow certain characters
        proposedMemo = proposedMemo.replaceAll(/[^a-zA-Z0-9 _\-\+:'\|\.,&\(\)]/, " ")
        
        // Collapse 3+ spaces to exactly 2 spaces
        proposedMemo = proposedMemo.replaceAll(/ {3,}/, "  ")
        
        // Enforce length limit
        return proposedMemo.length() > 500 ? proposedMemo.substring(0, 500) : proposedMemo
    }
    
    /**
     * Generate a reason for the Walmart match
     */
    private String generateWalmartMatchReason(YNABTransaction transaction, WalmartOrder order, double score) {
        List<String> reasons = []
        
        if (transaction.amount && order.totalAmount) {
            double amountDiff = Math.abs(transaction.getAmountInDollars() - order.totalAmount)
            if (amountDiff < 0.01) {
                reasons.add("exact amount match")
            } else {
                // Check if it matches a charge amount
                if (order.finalChargeAmounts) {
                    boolean matchesCharge = order.finalChargeAmounts.any { charge ->
                        Math.abs(transaction.getAmountInDollars() - charge) < 0.01
                    }
                    if (matchesCharge) {
                        reasons.add("matches charge amount")
                    }
                }
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
        
        if (isWalmartPayee(transaction.payee_name)) {
            reasons.add("Walmart payee")
        }
        
        return reasons.join(", ")
    }
    
    /**
     * Find multi-transaction matches for Walmart orders with multiple charges
     */
    private List<TransactionMatch> findMultiTransactionMatches(List<YNABTransaction> transactions, 
                                                                List<WalmartOrder> multiChargeOrders) {
        List<TransactionMatch> matches = []
        Set<String> matchedTransactionIds = new HashSet<>()
        
        // Group transactions by date proximity (within 7 days of each other)
        List<List<YNABTransaction>> transactionGroups = groupTransactionsByDateProximity(transactions, 7)
        
        // For each Walmart order with multiple charges
        multiChargeOrders.each { order ->
            if (!order.finalChargeAmounts || order.finalChargeAmounts.isEmpty()) {
                return
            }
            
            // Try to find a group of transactions that matches this order
            transactionGroups.each { group ->
                // Skip if any transaction in this group is already matched
                if (group.any { matchedTransactionIds.contains(it.id) }) {
                    return
                }
                
                // Calculate the sum of transaction amounts in this group
                BigDecimal groupSum = group.sum { it.getAmountInDollars() } as BigDecimal
                
                // Check if the sum matches the order total
                if (Math.abs(groupSum - order.totalAmount) < 0.01) {
                    // Verify all transactions are within date range of order
                    boolean allWithinDateRange = group.every { transaction ->
                        int daysDiff = calculateDaysDifference(transaction.date, order.orderDate)
                        daysDiff <= MAX_MATCH_DAYS_DIFFERENCE
                    }
                    
                    if (allWithinDateRange) {
                        // Calculate confidence score for multi-transaction match
                        double score = calculateMultiTransactionMatchScore(group, order)
                        
                        if (score >= 0.5) {  // Minimum confidence threshold
                            // Create a match for this group
                            TransactionMatch match = createMultiTransactionMatch(group, order, score)
                            matches.add(match)
                            
                            // Mark these transactions as matched
                            group.each { matchedTransactionIds.add(it.id) }
                        }
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Group transactions by date proximity
     */
    private List<List<YNABTransaction>> groupTransactionsByDateProximity(List<YNABTransaction> transactions, int maxDaysDiff) {
        List<List<YNABTransaction>> groups = []
        
        // Sort transactions by date
        List<YNABTransaction> sortedTransactions = transactions.sort { it.date }
        
        // Generate all possible combinations of 2 or more transactions
        for (int size = 2; size <= Math.min(sortedTransactions.size(), 5); size++) {
            generateCombinations(sortedTransactions, size).each { combination ->
                // Check if all transactions in this combination are within maxDaysDiff of each other
                boolean withinProximity = true
                for (int i = 0; i < combination.size() - 1; i++) {
                    int daysDiff = calculateDaysDifference(combination[i].date, combination[i + 1].date)
                    if (daysDiff > maxDaysDiff) {
                        withinProximity = false
                        break
                    }
                }
                
                if (withinProximity) {
                    groups.add(combination)
                }
            }
        }
        
        return groups
    }
    
    /**
     * Generate all combinations of a given size from a list
     */
    private List<List<YNABTransaction>> generateCombinations(List<YNABTransaction> list, int size) {
        List<List<YNABTransaction>> result = []
        
        if (size == 0) {
            result.add([])
            return result
        }
        
        if (list.isEmpty()) {
            return result
        }
        
        // Include first element
        YNABTransaction first = list.first()
        List<YNABTransaction> rest = list.tail()
        
        // Combinations that include the first element
        generateCombinations(rest, size - 1).each { combination ->
            result.add([first] + combination)
        }
        
        // Combinations that don't include the first element
        result.addAll(generateCombinations(rest, size))
        
        return result
    }
    
    /**
     * Calculate confidence score for multi-transaction match
     */
    private double calculateMultiTransactionMatchScore(List<YNABTransaction> transactions, WalmartOrder order) {
        double score = 0.0
        
        // Amount match (50% weight)
        // YNAB stores expenses as negative, so use absolute value
        BigDecimal transactionSum = transactions.sum { Math.abs(it.getAmountInDollars()) } as BigDecimal
        if (Math.abs(transactionSum - order.totalAmount) < 0.01) {
            score += 0.5
        } else {
            return 0.0  // No match if amounts don't match
        }
        
        // Date proximity (30% weight) - average distance from order date
        double avgDaysDiff = transactions.sum { transaction ->
            calculateDaysDifference(transaction.date, order.orderDate)
        } / transactions.size()
        
        double dateScore = Math.max(0.0, 1.0 - (avgDaysDiff / 7.0))
        score += dateScore * 0.3
        
        // Payee consistency (20% weight) - all transactions must be Walmart
        boolean allWalmartPayees = transactions.every { isWalmartPayee(it.payee_name) }
        if (allWalmartPayees) {
            score += 0.2
        }
        
        return Math.min(1.0, score)
    }
    
    /**
     * Create a multi-transaction match
     */
    private TransactionMatch createMultiTransactionMatch(List<YNABTransaction> transactions, WalmartOrder order, double score) {
        // Sort transactions by date to assign charge numbers
        List<YNABTransaction> sortedTransactions = transactions.sort { it.date }
        int totalCharges = sortedTransactions.size()
        
        // Generate memo for the first transaction (will be used as the primary memo)
        String proposedMemo = generateWalmartProposedMemo(
            sortedTransactions.first(), 
            order, 
            true, 
            1, 
            totalCharges
        )
        
        String matchReason = generateMultiTransactionMatchReason(sortedTransactions, order, score)
        
        return new TransactionMatch(sortedTransactions, order, proposedMemo, score, matchReason)
    }
    
    /**
     * Generate match reason for multi-transaction match
     */
    private String generateMultiTransactionMatchReason(List<YNABTransaction> transactions, WalmartOrder order, double score) {
        List<String> reasons = []
        
        BigDecimal transactionSum = transactions.sum { it.getAmountInDollars() } as BigDecimal
        if (Math.abs(transactionSum - order.totalAmount) < 0.01) {
            reasons.add("sum matches order total (${transactions.size()} charges)")
        }
        
        double avgDaysDiff = transactions.sum { transaction ->
            calculateDaysDifference(transaction.date, order.orderDate)
        } / transactions.size()
        
        if (avgDaysDiff <= 3) {
            reasons.add("close date match")
        }
        
        boolean allWalmartPayees = transactions.every { isWalmartPayee(it.payee_name) }
        if (allWalmartPayees) {
            reasons.add("all Walmart payees")
        }
        
        return reasons.join(", ")
    }
} 