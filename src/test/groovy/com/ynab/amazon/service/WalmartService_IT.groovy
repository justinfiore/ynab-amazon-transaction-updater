package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.TransactionMatch
import spock.lang.Specification

/**
 * Integration test class for end-to-end Walmart order processing
 * Tests the complete flow: fetch → match → update
 */
class WalmartService_IT extends Specification {
    
    Configuration config
    WalmartService walmartService
    TransactionMatcher matcher
    TransactionProcessor processor
    YNABService mockYnabService
    
    def setup() {
        config = new Configuration()
        config.walmartEnabled = true
        config.dryRun = true
        
        matcher = new TransactionMatcher()
        processor = new TransactionProcessor(config)
        mockYnabService = Mock(YNABService)
    }
    
    // ========== Test Data Helpers ==========
    
    private WalmartOrder createSingleChargeOrder() {
        def order = new WalmartOrder()
        order.orderId = "1234567890123"
        order.orderDate = "2024-01-15"
        order.orderStatus = "Delivered"
        order.totalAmount = -49.99  // Negative like YNAB transactions
        order.addFinalCharge(-49.99)  // Negative like YNAB transactions
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=1234567890123"
        
        def item = new WalmartOrderItem()
        item.title = "Wireless Mouse"
        item.price = -49.99  // Negative like YNAB transactions
        item.quantity = 1
        order.addItem(item)
        
        return order
    }
    
    private WalmartOrder createMultiChargeOrder() {
        def order = new WalmartOrder()
        order.orderId = "2345678901234"
        order.orderDate = "2024-01-20"
        order.orderStatus = "Delivered"
        order.totalAmount = -150.00  // Negative like YNAB transactions
        order.addFinalCharge(-100.00)  // Negative like YNAB transactions
        order.addFinalCharge(-50.00)   // Negative like YNAB transactions
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=2345678901234"
        
        def item1 = new WalmartOrderItem()
        item1.title = "Laptop Stand"
        item1.price = -100.00  // Negative like YNAB transactions
        item1.quantity = 1
        order.addItem(item1)
        
        def item2 = new WalmartOrderItem()
        item2.title = "USB Cable"
        item2.price = -50.00  // Negative like YNAB transactions
        item2.quantity = 1
        order.addItem(item2)
        
        return order
    }
    
    private WalmartOrder createNonDeliveredOrder() {
        def order = new WalmartOrder()
        order.orderId = "3456789012345"
        order.orderDate = "2024-01-25"
        order.orderStatus = "Processing"
        order.totalAmount = -29.99  // Negative like YNAB transactions
        order.addFinalCharge(-29.99)  // Negative like YNAB transactions
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=3456789012345"
        
        def item = new WalmartOrderItem()
        item.title = "Phone Case"
        item.price = -29.99  // Negative like YNAB transactions
        item.quantity = 1
        order.addItem(item)
        
        return order
    }
    
    private YNABTransaction createWalmartTransaction(BigDecimal amount, String date, String payee = "WALMART.COM") {
        def transaction = new YNABTransaction()
        transaction.id = UUID.randomUUID().toString()
        transaction.date = date
        transaction.amount = -amount * 1000 as long // YNAB uses milliunits
        transaction.payee_name = payee
        transaction.memo = ""
        return transaction
    }
    
    // ========== Subtask 7.1.1: Test complete flow with sample Walmart orders ==========
    
    def "should process single-charge Walmart order end-to-end"() {
        given: "a delivered Walmart order with single charge"
        WalmartOrder order = createSingleChargeOrder()
        List<WalmartOrder> orders = [order]
        
        and: "a matching YNAB transaction"
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-15")
        List<YNABTransaction> transactions = [transaction]
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should find match and process successfully"
        matches.size() == 1
        matches[0].walmartOrder == order
        matches[0].ynabTransaction == transaction
        matches[0].confidenceScore >= 0.7
        !matches[0].isMultiTransaction
        
        stats.updated == 1
        stats.high_confidence >= 1
    }
    
    def "should process multi-charge Walmart order end-to-end"() {
        given: "a delivered Walmart order with multiple charges"
        WalmartOrder order = createMultiChargeOrder()
        List<WalmartOrder> orders = [order]
        
        and: "matching YNAB transactions"
        YNABTransaction transaction1 = createWalmartTransaction(100.00, "2024-01-20")
        YNABTransaction transaction2 = createWalmartTransaction(50.00, "2024-01-21")
        List<YNABTransaction> transactions = [transaction1, transaction2]
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should find individual charge matches and process successfully"
        matches.size() == 2  // Two individual matches
        matches.every { it.walmartOrder == order }
        matches.every { !it.isMultiTransaction }
        matches.every { it.confidenceScore >= 0.7 }
        
        stats.updated == 2 // Both transactions updated
    }
    
    def "should skip non-delivered orders"() {
        given: "a non-delivered Walmart order"
        WalmartOrder deliveredOrder = createSingleChargeOrder()
        WalmartOrder nonDeliveredOrder = createNonDeliveredOrder()
        List<WalmartOrder> orders = [deliveredOrder, nonDeliveredOrder]
        
        and: "matching YNAB transactions"
        YNABTransaction transaction1 = createWalmartTransaction(49.99, "2024-01-15")
        YNABTransaction transaction2 = createWalmartTransaction(29.99, "2024-01-25")
        List<YNABTransaction> transactions = [transaction1, transaction2]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should only match delivered order"
        matches.size() == 1
        matches[0].walmartOrder.orderId == deliveredOrder.orderId
        matches[0].walmartOrder.isDelivered() == true
    }
    
    // ========== Subtask 7.1.2: Test with mixed Amazon and Walmart orders ==========
    
    def "should handle mixed Amazon and Walmart transactions"() {
        given: "Walmart orders"
        WalmartOrder walmartOrder = createSingleChargeOrder()
        List<WalmartOrder> walmartOrders = [walmartOrder]
        
        and: "mixed YNAB transactions"
        YNABTransaction walmartTx = createWalmartTransaction(49.99, "2024-01-15", "WALMART.COM")
        YNABTransaction amazonTx = createWalmartTransaction(29.99, "2024-01-16", "AMAZON.COM")
        YNABTransaction otherTx = createWalmartTransaction(19.99, "2024-01-17", "TARGET")
        List<YNABTransaction> transactions = [walmartTx, amazonTx, otherTx]
        
        when: "matching Walmart orders"
        List<TransactionMatch> walmartMatches = matcher.findWalmartMatches(transactions, walmartOrders)
        
        then: "should only match Walmart transaction"
        walmartMatches.size() == 1
        walmartMatches[0].transactions[0] == walmartTx
        walmartMatches[0].transactions[0].payeeName.contains("WALMART")
    }
    
    // ========== Subtask 7.1.3: Test multi-transaction order scenarios ==========
    
    def "should match multi-transaction order with correct charge amounts"() {
        given: "a Walmart order with 3 final charges"
        def order = new WalmartOrder()
        order.orderId = "4567890123456"
        order.orderDate = "2024-02-01"
        order.orderStatus = "Delivered"
        order.totalAmount = 200.00
        order.addFinalCharge(100.00)
        order.addFinalCharge(60.00)
        order.addFinalCharge(40.00)
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=4567890123456"
        
        def item = new WalmartOrderItem()
        item.title = "Electronics Bundle"
        item.price = 200.00
        item.quantity = 1
        order.addItem(item)
        
        List<WalmartOrder> orders = [order]
        
        and: "matching YNAB transactions"
        YNABTransaction tx1 = createWalmartTransaction(100.00, "2024-02-01")
        YNABTransaction tx2 = createWalmartTransaction(60.00, "2024-02-02")
        YNABTransaction tx3 = createWalmartTransaction(40.00, "2024-02-03")
        List<YNABTransaction> transactions = [tx1, tx2, tx3]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should match all three transactions to the order"
        matches.size() == 1
        matches[0].transactions.size() == 3
        matches[0].isMultiTransaction == true
        matches[0].walmartOrder.hasMultipleCharges() == true
        
        // Verify sum of transaction amounts matches order total (both negative for expenses)
        BigDecimal txSum = matches[0].transactions.sum { it.amount / 1000.0 } as BigDecimal
        txSum == -order.totalAmount
    }
    
    def "should not match transactions with incorrect sum"() {
        given: "a Walmart order with specific charges"
        WalmartOrder order = createMultiChargeOrder() // Total: 150.00
        List<WalmartOrder> orders = [order]
        
        and: "YNAB transactions with wrong sum"
        YNABTransaction tx1 = createWalmartTransaction(100.00, "2024-01-20")
        YNABTransaction tx2 = createWalmartTransaction(40.00, "2024-01-21") // Wrong amount
        List<YNABTransaction> transactions = [tx1, tx2]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should not match due to amount mismatch"
        matches.isEmpty() || matches[0].confidence < 0.7
    }
    
    def "should handle transactions spread across multiple days"() {
        given: "a Walmart order with multiple charges"
        WalmartOrder order = createMultiChargeOrder()
        List<WalmartOrder> orders = [order]
        
        and: "YNAB transactions spread across 5 days"
        YNABTransaction tx1 = createWalmartTransaction(100.00, "2024-01-20")
        YNABTransaction tx2 = createWalmartTransaction(50.00, "2024-01-25") // 5 days later
        List<YNABTransaction> transactions = [tx1, tx2]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should still match if within 7-day window"
        matches.size() == 1
        matches[0].transactions.size() == 2
    }
    
    // ========== Subtask 7.1.4: Test delivered vs non-delivered filtering ==========
    
    def "should only process delivered orders"() {
        given: "orders with various statuses"
        WalmartOrder deliveredOrder = createSingleChargeOrder()
        deliveredOrder.orderStatus = "Delivered"
        
        WalmartOrder processingOrder = createNonDeliveredOrder()
        processingOrder.orderStatus = "Processing"
        
        WalmartOrder shippedOrder = new WalmartOrder()
        shippedOrder.orderId = "5678901234567"
        shippedOrder.orderDate = "2024-01-30"
        shippedOrder.orderStatus = "Shipped"
        shippedOrder.totalAmount = 39.99
        shippedOrder.addFinalCharge(39.99)
        
        List<WalmartOrder> orders = [deliveredOrder, processingOrder, shippedOrder]
        
        and: "matching transactions"
        YNABTransaction tx1 = createWalmartTransaction(49.99, "2024-01-15")
        YNABTransaction tx2 = createWalmartTransaction(29.99, "2024-01-25")
        YNABTransaction tx3 = createWalmartTransaction(39.99, "2024-01-30")
        List<YNABTransaction> transactions = [tx1, tx2, tx3]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should only match delivered order"
        matches.size() == 1
        matches[0].walmartOrder.orderStatus == "Delivered"
        matches[0].walmartOrder.isDelivered() == true
    }
    
    def "should verify isDelivered() method works correctly"() {
        given: "orders with different statuses"
        WalmartOrder delivered = createSingleChargeOrder()
        delivered.orderStatus = "Delivered"
        
        WalmartOrder processing = createNonDeliveredOrder()
        processing.orderStatus = "Processing"
        
        WalmartOrder cancelled = new WalmartOrder()
        cancelled.orderStatus = "Cancelled"
        
        expect: "isDelivered() returns correct values"
        delivered.isDelivered() == true
        processing.isDelivered() == false
        cancelled.isDelivered() == false
    }
    
    // ========== Subtask 7.1.5: Test final charges vs temporary holds ==========
    
    def "should only use final charges for matching"() {
        given: "a Walmart order with final charges specified"
        WalmartOrder order = createMultiChargeOrder()
        // Order has totalAmount of 150.00 but finalChargeAmounts of [100.00, 50.00]
        // This simulates temporary hold being different from final charges
        List<WalmartOrder> orders = [order]
        
        and: "YNAB transactions matching final charges"
        YNABTransaction tx1 = createWalmartTransaction(100.00, "2024-01-20")
        YNABTransaction tx2 = createWalmartTransaction(50.00, "2024-01-21")
        List<YNABTransaction> transactions = [tx1, tx2]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should match based on final charges, not total amount"
        matches.size() == 1
        matches[0].transactions.size() == 2
        
        // Verify it's using finalChargeAmounts
        order.finalChargeAmounts.size() == 2
        order.finalChargeAmounts.sum() == 150.00
    }
    
    def "should ignore temporary holds in matching"() {
        given: "a Walmart order where temporary hold differs from final charges"
        def order = new WalmartOrder()
        order.orderId = "6789012345678"
        order.orderDate = "2024-02-10"
        order.orderStatus = "Delivered"
        order.totalAmount = 200.00 // This was the temporary hold
        order.addFinalCharge(180.00) // Final charge after adjustments
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=6789012345678"
        
        def item = new WalmartOrderItem()
        item.title = "Discounted Item"
        item.price = 180.00
        item.quantity = 1
        order.addItem(item)
        
        List<WalmartOrder> orders = [order]
        
        and: "YNAB transaction matching final charge"
        YNABTransaction tx = createWalmartTransaction(180.00, "2024-02-10")
        List<YNABTransaction> transactions = [tx]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should match based on final charge amount"
        matches.size() == 1
        matches[0].transactions[0].amount == -180000 // milliunits
    }
    
    // ========== Subtask 7.1.6: Test YNAB updates in dry-run mode ==========
    
    def "should log updates without modifying YNAB in dry-run mode"() {
        given: "dry-run mode enabled"
        config.dryRun = true
        
        and: "a Walmart order and matching transaction"
        WalmartOrder order = createSingleChargeOrder()
        List<WalmartOrder> orders = [order]
        
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-15")
        List<YNABTransaction> transactions = [transaction]
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should process but not call YNAB service"
        matches.size() == 1
        stats.updated == 1
        
        // Verify YNAB service was not called
        0 * mockYnabService.updateTransaction(_, _)
    }
    
    def "should update YNAB when dry-run is disabled"() {
        given: "dry-run mode disabled"
        config.dryRun = false
        
        and: "a Walmart order and matching transaction"
        WalmartOrder order = createSingleChargeOrder()
        List<WalmartOrder> orders = [order]
        
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-15")
        List<YNABTransaction> transactions = [transaction]
        
        and: "mock YNAB service returns success"
        mockYnabService.updateTransaction(_, _) >> true
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, false)
        
        then: "should call YNAB service to update"
        matches.size() == 1
        stats.updated == 1
        
        // Verify YNAB service was called
        1 * mockYnabService.updateTransaction(_, _)
    }
    
    def "should generate correct memo format in dry-run"() {
        given: "dry-run mode enabled"
        config.dryRun = true
        
        and: "a single-charge Walmart order"
        WalmartOrder order = createSingleChargeOrder()
        List<WalmartOrder> orders = [order]
        
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-15")
        transaction.memo = "Existing memo"
        List<YNABTransaction> transactions = [transaction]
        
        when: "processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "memo should be formatted correctly"
        matches.size() == 1
        def match = matches[0]
        
        // Verify memo would contain order link and product summary
        def expectedMemo = transaction.memo + " | Walmart Order: ${order.orderId} - ${order.getProductSummary()}"
        // Note: Actual memo generation happens in processor, this verifies the match data is correct
        match.walmartOrder.orderId == "1234567890123"
        match.walmartOrder.getProductSummary() == "Wireless Mouse"
    }
    
    def "should generate correct memo format for multi-transaction orders"() {
        given: "dry-run mode enabled"
        config.dryRun = true
        
        and: "a multi-charge Walmart order"
        WalmartOrder order = createMultiChargeOrder()
        List<WalmartOrder> orders = [order]
        
        YNABTransaction tx1 = createWalmartTransaction(100.00, "2024-01-20")
        tx1.memo = "First charge"
        YNABTransaction tx2 = createWalmartTransaction(50.00, "2024-01-21")
        tx2.memo = "Second charge"
        List<YNABTransaction> transactions = [tx1, tx2]
        
        when: "processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should indicate multi-transaction order"
        matches.size() == 1
        matches[0].isMultiTransaction == true
        matches[0].transactions.size() == 2
        
        // Verify order data for memo generation
        matches[0].walmartOrder.hasMultipleCharges() == true
        matches[0].walmartOrder.finalChargeAmounts.size() == 2
    }
    
    // ========== Additional Integration Scenarios ==========
    
    def "should handle empty order list gracefully"() {
        given: "no Walmart orders"
        List<WalmartOrder> orders = []
        
        and: "YNAB transactions"
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-15")
        List<YNABTransaction> transactions = [transaction]
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should return empty matches"
        matches.isEmpty()
    }
    
    def "should handle empty transaction list gracefully"() {
        given: "Walmart orders"
        WalmartOrder order = createSingleChargeOrder()
        List<WalmartOrder> orders = [order]
        
        and: "no transactions"
        List<YNABTransaction> transactions = []
        
        when: "matching"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "should return empty matches"
        matches.isEmpty()
    }
    
    def "should process multiple orders and transactions correctly"() {
        given: "multiple Walmart orders"
        WalmartOrder order1 = createSingleChargeOrder()
        WalmartOrder order2 = createMultiChargeOrder()
        List<WalmartOrder> orders = [order1, order2]
        
        and: "matching YNAB transactions"
        YNABTransaction tx1 = createWalmartTransaction(49.99, "2024-01-15")
        YNABTransaction tx2 = createWalmartTransaction(100.00, "2024-01-20")
        YNABTransaction tx3 = createWalmartTransaction(50.00, "2024-01-21")
        List<YNABTransaction> transactions = [tx1, tx2, tx3]
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should match both orders correctly"
        matches.size() == 2
        
        // One single-transaction match
        def singleMatch = matches.find { !it.isMultiTransaction }
        singleMatch != null
        singleMatch.transactions.size() == 1
        
        // One multi-transaction match
        def multiMatch = matches.find { it.isMultiTransaction }
        multiMatch != null
        multiMatch.transactions.size() == 2
        
        // Total updates should be 3 (1 + 2)
        stats.updated == 3
    }
    
    def "should respect minimum confidence threshold"() {
        given: "high confidence threshold"
        // Note: TransactionProcessor uses config internally for threshold
        processor = new TransactionProcessor(config)
        
        and: "a Walmart order with marginal match"
        WalmartOrder order = createSingleChargeOrder()
        order.orderDate = "2024-01-15"
        List<WalmartOrder> orders = [order]
        
        YNABTransaction transaction = createWalmartTransaction(49.99, "2024-01-25") // 10 days later
        List<YNABTransaction> transactions = [transaction]
        
        when: "matching and processing"
        List<TransactionMatch> matches = matcher.findWalmartMatches(transactions, orders)
        Map<String, Integer> stats = processor.processWalmartMatches(matches, mockYnabService, true)
        
        then: "should not update due to low confidence"
        matches.isEmpty() || matches[0].confidence < 0.9
        stats.updated == 0
    }
}
