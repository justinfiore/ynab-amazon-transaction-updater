package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.service.YNABService
import com.ynab.amazon.service.AmazonService
import com.ynab.amazon.service.WalmartService
import com.ynab.amazon.service.TransactionMatcher
import com.ynab.amazon.service.TransactionProcessor
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Integration test class for end-to-end Walmart transaction processing flow
 * Tests the complete workflow: fetch → match → update with mixed Amazon and Walmart orders
 */
class YNABAmazonTransactionUpdater_Walmart_IT extends Specification {
    
    @TempDir
    File tempDir
    
    Configuration config
    YNABService mockYnabService
    AmazonService mockAmazonService
    WalmartService mockWalmartService
    TransactionMatcher matcher
    TransactionProcessor processor
    
    def setup() {
        config = new Configuration()
        config.dryRun = true
        config.walmartEnabled = true
        config.lookBackDays = 30
        
        mockYnabService = Mock(YNABService)
        mockAmazonService = Mock(AmazonService)
        mockWalmartService = Mock(WalmartService)
        matcher = new TransactionMatcher()
        processor = new TransactionProcessor(config)
    }
    
    // ========== Test Data Helpers ==========
    
    private WalmartOrder createDeliveredWalmartOrder(String orderId, String date, BigDecimal totalAmount, List<BigDecimal> finalCharges, String productTitle) {
        def order = new WalmartOrder()
        order.orderId = orderId
        order.orderDate = date
        order.orderStatus = "Delivered"
        order.totalAmount = -totalAmount  // Negative for expenses
        finalCharges.each { charge ->
            order.addFinalCharge(-charge)  // Negative for expenses
        }
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=${orderId}"
        
        def item = new WalmartOrderItem()
        item.title = productTitle
        item.price = -totalAmount  // Negative for expenses
        item.quantity = 1
        order.addItem(item)
        
        return order
    }
    
    private WalmartOrder createNonDeliveredWalmartOrder(String orderId, String date, BigDecimal totalAmount, String status) {
        def order = new WalmartOrder()
        order.orderId = orderId
        order.orderDate = date
        order.orderStatus = status
        order.totalAmount = -totalAmount  // Negative for expenses
        order.addFinalCharge(-totalAmount)  // Negative for expenses
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=${orderId}"
        
        def item = new WalmartOrderItem()
        item.title = "Non-delivered Item"
        item.price = -totalAmount  // Negative for expenses
        item.quantity = 1
        order.addItem(item)
        
        return order
    }
    
    private AmazonOrder createAmazonOrder(String orderId, String date, BigDecimal totalAmount, String productTitle) {
        def order = new AmazonOrder()
        order.orderId = orderId
        order.orderDate = date
        order.totalAmount = totalAmount
        
        def item = new AmazonOrderItem()
        item.title = productTitle
        item.price = totalAmount
        item.quantity = 1
        order.addItem(item)
        
        return order
    }
    
    private YNABTransaction createYNABTransaction(String id, String date, BigDecimal amount, String payee, String memo = "") {
        def transaction = new YNABTransaction()
        transaction.id = id
        transaction.date = date
        transaction.amount = (-amount * 1000) as long  // YNAB uses milliunits, negative for expenses
        transaction.payee_name = payee
        transaction.memo = memo
        transaction.cleared = "cleared"
        transaction.approved = "true"
        return transaction
    }
    
    // ========== End-to-End Flow Tests ==========
    
    def "should process complete flow with single-charge Walmart order"() {
        given: "YNAB transactions including Walmart transaction"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM", "Existing memo"),
            createYNABTransaction("tx2", "2024-01-16", 29.99, "AMAZON.COM", "Amazon purchase")
        ]
        
        and: "delivered Walmart order"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123456", "2024-01-15", 49.99, [49.99], "Wireless Mouse")
        ]
        
        and: "Amazon order"
        def amazonOrders = [
            createAmazonOrder("AMZ789012", "2024-01-16", 29.99, "Book")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockAmazonService.getOrders() >> amazonOrders
        
        when: "executing complete workflow"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, true)
        
        then: "should find and process Walmart match"
        walmartMatches.size() == 1
        walmartMatches[0].ynabTransaction.payee_name == "WALMART.COM"
        walmartMatches[0].walmartOrder.orderId == "WM123456"
        walmartMatches[0].isHighConfidence() || walmartMatches[0].isMediumConfidence()
        !walmartMatches[0].isMultiTransaction
        
        walmartStats.updated == 1
        walmartStats.high_confidence >= 1
        
        // Verify no YNAB calls in dry-run mode
        0 * mockYnabService.updateTransactionMemo(_, _)
    }
    
    def "should process complete flow with multi-charge Walmart order"() {
        given: "YNAB transactions for multi-charge order"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-20", 100.00, "WALMART.COM", "First charge"),
            createYNABTransaction("tx2", "2024-01-21", 50.00, "WALMART.COM", "Second charge"),
            createYNABTransaction("tx3", "2024-01-22", 25.99, "TARGET", "Other store")
        ]
        
        and: "Walmart order with multiple final charges"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM234567", "2024-01-20", 150.00, [100.00, 50.00], "Electronics Bundle")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockAmazonService.getOrders() >> []
        
        when: "executing workflow"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, true)
        
        then: "should match both Walmart transactions individually"
        walmartMatches.size() == 2  // Two individual charge matches
        walmartMatches.every { it.walmartOrder.orderId == "WM234567" }
        walmartMatches.every { !it.isMultiTransaction }  // Individual charge matching
        walmartMatches.every { it.isHighConfidence() || it.isMediumConfidence() }
        
        // Verify both transactions are Walmart transactions
        def matchedTransactionIds = walmartMatches.collect { it.ynabTransaction.id }
        matchedTransactionIds.containsAll(["tx1", "tx2"])
        !matchedTransactionIds.contains("tx3")  // TARGET transaction not matched
        
        walmartStats.updated == 2
    }
    
    def "should handle mixed Amazon and Walmart orders correctly"() {
        given: "mixed YNAB transactions"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM"),
            createYNABTransaction("tx2", "2024-01-16", 29.99, "AMAZON.COM"),
            createYNABTransaction("tx3", "2024-01-17", 19.99, "WALMART ONLINE"),
            createYNABTransaction("tx4", "2024-01-18", 39.99, "AMZN MKTP US")
        ]
        
        and: "mixed retailer orders"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123", "2024-01-15", 49.99, [49.99], "Walmart Item 1"),
            createDeliveredWalmartOrder("WM456", "2024-01-17", 19.99, [19.99], "Walmart Item 2")
        ]
        
        def amazonOrders = [
            createAmazonOrder("AMZ123", "2024-01-16", 29.99, "Amazon Item 1"),
            createAmazonOrder("AMZ456", "2024-01-18", 39.99, "Amazon Item 2")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockAmazonService.getOrders() >> amazonOrders
        
        when: "processing both Amazon and Walmart matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        
        // Process Amazon matches first (as in main application)
        def amazonMatches = matcher.findMatches(unprocessedTransactions, amazonOrders)
        def amazonStats = processor.updateTransactions(amazonMatches, mockYnabService, true)
        
        // Process Walmart matches
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, true)
        
        then: "should match appropriate transactions to each retailer"
        // Amazon matches
        amazonMatches.size() == 2
        amazonMatches.every { it.amazonOrder != null }
        def amazonTxIds = amazonMatches.collect { it.ynabTransaction.id }
        amazonTxIds.containsAll(["tx2", "tx4"])
        
        // Walmart matches
        walmartMatches.size() == 2
        walmartMatches.every { it.walmartOrder != null }
        def walmartTxIds = walmartMatches.collect { it.ynabTransaction.id }
        walmartTxIds.containsAll(["tx1", "tx3"])
        
        // Verify no cross-contamination
        amazonTxIds.intersect(walmartTxIds).isEmpty()
        
        amazonStats.updated == 2
        walmartStats.updated == 2
    }
    
    def "should filter out non-delivered Walmart orders"() {
        given: "YNAB transactions"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM"),
            createYNABTransaction("tx2", "2024-01-16", 29.99, "WALMART.COM"),
            createYNABTransaction("tx3", "2024-01-17", 19.99, "WALMART.COM")
        ]
        
        and: "Walmart orders with different statuses"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123", "2024-01-15", 49.99, [49.99], "Delivered Item"),
            createNonDeliveredWalmartOrder("WM456", "2024-01-16", 29.99, "Processing"),
            createNonDeliveredWalmartOrder("WM789", "2024-01-17", 19.99, "Shipped")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        
        when: "processing matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        
        then: "should only match delivered order"
        walmartMatches.size() == 1
        walmartMatches[0].walmartOrder.orderId == "WM123"
        walmartMatches[0].walmartOrder.orderStatus == "Delivered"
        walmartMatches[0].walmartOrder.isDelivered() == true
    }
    
    def "should handle final charges vs temporary holds correctly"() {
        given: "YNAB transactions matching final charges"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-20", 90.00, "WALMART.COM"),  // Matches final charge
            createYNABTransaction("tx2", "2024-01-21", 40.00, "WALMART.COM")   // Matches final charge
        ]
        
        and: "Walmart order with different total vs final charges"
        def order = new WalmartOrder()
        order.orderId = "WM999"
        order.orderDate = "2024-01-20"
        order.orderStatus = "Delivered"
        order.totalAmount = -150.00  // Original temporary hold amount
        // Final charges after adjustments (discounts, etc.)
        order.addFinalCharge(-90.00)
        order.addFinalCharge(-40.00)
        order.orderUrl = "https://www.walmart.com/orders/details?orderId=WM999"
        
        def item = new WalmartOrderItem()
        item.title = "Adjusted Price Item"
        item.price = -130.00  // Final item price
        item.quantity = 1
        order.addItem(item)
        
        def walmartOrders = [order]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        
        when: "processing matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        
        then: "should match based on final charges, not total amount"
        walmartMatches.size() == 2  // Two individual charge matches
        walmartMatches.every { it.walmartOrder.orderId == "WM999" }
        
        // Verify final charges are used for matching
        order.finalChargeAmounts.size() == 2
        order.finalChargeAmounts.sum() == -130.00  // Sum of final charges
        order.totalAmount == -150.00  // Original temporary hold (different)
        
        // Verify transactions match final charges, not total
        def matchedAmounts = walmartMatches.collect { (it.ynabTransaction.amount / 1000.0) as BigDecimal }
        matchedAmounts.sort() == [-90.00, -40.00].sort()
    }
    
    def "should verify YNAB updates in dry-run vs live mode"() {
        given: "configuration for live mode"
        config.dryRun = false
        processor = new TransactionProcessor(config)
        
        and: "YNAB transaction and Walmart order"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM", "Original memo")
        ]
        
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123", "2024-01-15", 49.99, [49.99], "Test Product")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockYnabService.updateTransactionMemo(_, _) >> true
        
        when: "processing in live mode"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, false)
        
        then: "should call YNAB service to update transaction"
        walmartMatches.size() == 1
        walmartStats.updated == 1
        
        // Verify YNAB service was called with correct parameters
        1 * mockYnabService.updateTransactionMemo("tx1", _) >> { String id, String memo ->
            assert memo.contains("Walmart Order: WM123")
            assert memo.contains("Test Product")
            assert memo.startsWith("Original memo |")
            return true
        }
    }
    
    def "should handle empty order lists gracefully"() {
        given: "YNAB transactions but no orders"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM")
        ]
        
        and: "empty order lists"
        def walmartOrders = []
        def amazonOrders = []
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockAmazonService.getOrders() >> amazonOrders
        
        when: "processing matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def amazonMatches = matcher.findMatches(unprocessedTransactions, amazonOrders)
        
        then: "should handle gracefully with no matches"
        walmartMatches.isEmpty()
        amazonMatches.isEmpty()
        unprocessedTransactions.size() == 1  // Transaction remains unprocessed
    }
    
    def "should handle empty transaction list gracefully"() {
        given: "no YNAB transactions"
        def ynabTransactions = []
        
        and: "available orders"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123", "2024-01-15", 49.99, [49.99], "Test Product")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        
        when: "processing matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        
        then: "should handle gracefully with no matches"
        unprocessedTransactions.isEmpty()
        walmartMatches.isEmpty()
    }
    
    def "should process multiple orders with various scenarios"() {
        given: "complex mix of transactions"
        def ynabTransactions = [
            // Single Walmart charge
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM"),
            // Multi-charge Walmart order
            createYNABTransaction("tx2", "2024-01-20", 100.00, "WALMART ONLINE"),
            createYNABTransaction("tx3", "2024-01-21", 50.00, "WALMART ONLINE"),
            // Amazon transactions
            createYNABTransaction("tx4", "2024-01-16", 29.99, "AMAZON.COM"),
            createYNABTransaction("tx5", "2024-01-22", 19.99, "AMZN MKTP US"),
            // Non-matching transaction
            createYNABTransaction("tx6", "2024-01-25", 39.99, "TARGET")
        ]
        
        and: "corresponding orders"
        def walmartOrders = [
            createDeliveredWalmartOrder("WM001", "2024-01-15", 49.99, [49.99], "Single Item"),
            createDeliveredWalmartOrder("WM002", "2024-01-20", 150.00, [100.00, 50.00], "Multi Item Bundle"),
            createNonDeliveredWalmartOrder("WM003", "2024-01-25", 39.99, "Processing")  // Should be filtered out
        ]
        
        def amazonOrders = [
            createAmazonOrder("AMZ001", "2024-01-16", 29.99, "Amazon Book"),
            createAmazonOrder("AMZ002", "2024-01-22", 19.99, "Amazon Gadget")
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        mockAmazonService.getOrders() >> amazonOrders
        
        when: "processing complete workflow"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        
        // Process Amazon matches
        def amazonMatches = matcher.findMatches(unprocessedTransactions, amazonOrders)
        def amazonStats = processor.updateTransactions(amazonMatches, mockYnabService, true)
        
        // Process Walmart matches
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, true)
        
        then: "should process all matching transactions correctly"
        // Amazon matches
        amazonMatches.size() == 2
        def amazonTxIds = amazonMatches.collect { it.ynabTransaction.id }
        amazonTxIds.containsAll(["tx4", "tx5"])
        
        // Walmart matches (3 individual charge matches: 1 single + 2 from multi-charge order)
        walmartMatches.size() == 3
        def walmartTxIds = walmartMatches.collect { it.ynabTransaction.id }
        walmartTxIds.containsAll(["tx1", "tx2", "tx3"])
        
        // Verify non-delivered order was filtered out
        walmartMatches.every { it.walmartOrder.isDelivered() }
        
        // Verify TARGET transaction was not matched
        def allMatchedIds = (amazonTxIds + walmartTxIds)
        !allMatchedIds.contains("tx6")
        
        // Verify statistics
        amazonStats.updated == 2
        walmartStats.updated == 3
        
        // Verify no cross-contamination between retailers
        amazonTxIds.intersect(walmartTxIds).isEmpty()
    }
    
    def "should respect confidence thresholds"() {
        given: "marginal match scenario"
        processor = new TransactionProcessor(config)
        
        and: "marginal match scenario"
        def ynabTransactions = [
            createYNABTransaction("tx1", "2024-01-15", 49.99, "WALMART.COM")
        ]
        
        def walmartOrders = [
            createDeliveredWalmartOrder("WM123", "2024-01-25", 49.99, [49.99], "Test Product")  // 10 days later
        ]
        
        and: "mock services return data"
        mockYnabService.getTransactions() >> ynabTransactions
        mockWalmartService.getOrders() >> walmartOrders
        
        when: "processing matches"
        def unprocessedTransactions = processor.getUnprocessedTransactions(ynabTransactions)
        def walmartMatches = matcher.findWalmartMatches(unprocessedTransactions, walmartOrders)
        def walmartStats = processor.processWalmartMatches(walmartMatches, mockYnabService, true)
        
        then: "should not update due to low confidence"
        // Match might be found but with low confidence due to date mismatch
        if (!walmartMatches.isEmpty()) {
            walmartMatches[0].isLowConfidence()
        }
        walmartStats.updated == 0  // No updates due to low confidence
    }
}