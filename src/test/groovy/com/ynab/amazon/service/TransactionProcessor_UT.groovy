package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import spock.lang.Specification
import spock.lang.TempDir
import java.lang.reflect.Field

/**
 * Test class for the TransactionProcessor service
 */
class TransactionProcessor_UT extends Specification {
    
    @TempDir
    File tempFolder
    
    Configuration mockConfig
    YNABService mockYnabService
    File processedTransactionsFile
    
    def setup() {
        // Create mocks
        mockConfig = Mock(Configuration)
        mockYnabService = Mock(YNABService)
        
        // Create a temporary file for processed transactions
        processedTransactionsFile = new File(tempFolder, "processed_transactions.json")
        processedTransactionsFile.createNewFile()
        processedTransactionsFile.text = '{"processed_transaction_ids": [], "last_updated": ""}'
        
        // Configure mock to return the temporary file path
        mockConfig.getProcessedTransactionsFile() >> processedTransactionsFile.absolutePath
    }
    
    def "getUnprocessedTransactions should filter out processed transactions"() {
        given: "a processor with processed tx1"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "we set processed transaction ids using reflection"
        Field field = TransactionProcessor.class.getDeclaredField("processedTransactionIds")
        field.setAccessible(true)
        field.set(processor, ["tx1"] as Set)
        
        and: "a mix of processed and unprocessed transactions"
        def processedTransaction = new YNABTransaction(id: "tx1", memo: "Already processed with items: Product A")
        def unprocessedTransaction = new YNABTransaction(id: "tx2", memo: null)
        def allTransactions = [processedTransaction, unprocessedTransaction]
        
        when: "getUnprocessedTransactions is called"
        def result = processor.getUnprocessedTransactions(allTransactions)
        
        then: "only unprocessed transactions are returned"
        result.size() == 1
        result[0].id == "tx2"
    }
    
    def "updateTransactions should update high confidence matches in normal mode"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "high confidence matches and normal run mode"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo")
        def order = new AmazonOrder(orderId: "order1")
        def match = new TransactionMatch(
            ynabTransaction: ynabTx,
            amazonOrder: order,
            proposedMemo: "Updated memo",
            confidenceScore: 0.9,  // High confidence
            matchReason: "Test match"
        )
        
        and: "YNABService returns success"
        mockYnabService.updateTransactionMemo("tx1", "Updated memo") >> true
        
        when: "updateTransactions is called in normal mode"
        def result = processor.updateTransactions([match], mockYnabService, false)
        
        then: "transactions should be updated"
        result.updated == 1
        result.high_confidence == 1
    }
    
    def "updateTransactions should not make API calls in dry run mode"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "high confidence matches and dry run mode"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo")
        def order = new AmazonOrder(orderId: "order1")
        def match = new TransactionMatch(
            ynabTransaction: ynabTx,
            amazonOrder: order,
            proposedMemo: "Updated memo",
            confidenceScore: 0.9,  // High confidence
            matchReason: "Test match"
        )
        
        when: "updateTransactions is called in dry run mode"
        def result = processor.updateTransactions([match], mockYnabService, true)
        
        then: "no actual updates should be made"
        result.updated == 1
        result.high_confidence == 1
        0 * mockYnabService.updateTransactionMemo(_, _)  // Verify YNABService was not called
    }
    
    def "updateTransactions should ignore low confidence matches"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "low confidence match"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo")
        def order = new AmazonOrder(orderId: "order1")
        def match = new TransactionMatch(
            ynabTransaction: ynabTx,
            amazonOrder: order,
            proposedMemo: "Updated memo",
            confidenceScore: 0.4,  // Low confidence
            matchReason: "Test match"
        )
        
        when: "updateTransactions is called"
        def result = processor.updateTransactions([match], mockYnabService, false)
        
        then: "no updates should be made"
        result.updated == 0
        result.low_confidence == 1
        0 * mockYnabService.updateTransactionMemo(_, _)  // Verify YNABService was not called
    }
    
    // Walmart-specific tests
    
    def "processWalmartMatches should update single transaction matches"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a high confidence single-transaction Walmart match"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo", payee_name: "WALMART.COM", amount: -5000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 50.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Test Product", price: 50.00, quantity: 1))
        
        def match = new TransactionMatch(ynabTx, order, "Proposed memo", 0.9, "Test match")
        
        when: "processWalmartMatches is called"
        def result = processor.processWalmartMatches([match], mockYnabService, false)
        
        then: "transaction should be updated"
        result.updated == 1
        result.high_confidence == 1
        1 * mockYnabService.updateTransactionMemo("tx1", { String memo ->
            memo.contains("Walmart Order: 123456") && 
            memo.contains("Test Product") &&
            !memo.contains("Charge")
        }) >> true
    }
    
    def "processWalmartMatches should update multi-transaction matches"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a high confidence multi-transaction Walmart match"
        def ynabTx1 = new YNABTransaction(id: "tx1", memo: "Original memo 1", payee_name: "WALMART.COM", amount: -10000)
        def ynabTx2 = new YNABTransaction(id: "tx2", memo: "Original memo 2", payee_name: "WALMART.COM", amount: -5000)
        
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 150.00,
            orderStatus: "Delivered"
        )
        order.addFinalCharge(100.00)
        order.addFinalCharge(50.00)
        order.addItem(new WalmartOrderItem(title: "Product A", price: 100.00, quantity: 1))
        order.addItem(new WalmartOrderItem(title: "Product B", price: 50.00, quantity: 1))
        
        def match = new TransactionMatch([ynabTx1, ynabTx2], order, "Proposed memo", 0.85, "Multi-transaction match")
        
        when: "processWalmartMatches is called"
        def result = processor.processWalmartMatches([match], mockYnabService, false)
        
        then: "both transactions should be updated"
        result.updated == 2
        result.high_confidence == 1
        1 * mockYnabService.updateTransactionMemo("tx1", { String memo ->
            memo.contains("Walmart Order: 123456") && 
            memo.contains("Charge 1 of 2") &&
            memo.contains("2 items:")
        }) >> true
        1 * mockYnabService.updateTransactionMemo("tx2", { String memo ->
            memo.contains("Walmart Order: 123456") && 
            memo.contains("Charge 2 of 2") &&
            memo.contains("2 items:")
        }) >> true
    }
    
    def "processWalmartMatches should preserve existing memo content"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a Walmart match with existing memo"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "My custom note", payee_name: "WALMART.COM", amount: -5000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 50.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Test Product", price: 50.00, quantity: 1))
        
        def match = new TransactionMatch(ynabTx, order, "Proposed memo", 0.9, "Test match")
        
        when: "processWalmartMatches is called"
        def result = processor.processWalmartMatches([match], mockYnabService, false)
        
        then: "existing memo should be preserved"
        result.updated == 1
        1 * mockYnabService.updateTransactionMemo("tx1", { String memo ->
            memo.startsWith("My custom note |") && 
            memo.contains("Walmart Order: 123456")
        }) >> true
    }
    
    def "processWalmartMatches should not make API calls in dry run mode"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a high confidence Walmart match"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo", payee_name: "WALMART.COM", amount: -5000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 50.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Test Product", price: 50.00, quantity: 1))
        
        def match = new TransactionMatch(ynabTx, order, "", 0.9, "Test match")
        
        when: "processWalmartMatches is called in dry run mode"
        def result = processor.processWalmartMatches([match], mockYnabService, true)
        
        then: "no actual updates should be made"
        result.updated == 1
        result.high_confidence == 1
        0 * mockYnabService.updateTransactionMemo(_, _)
    }
    
    def "processWalmartMatches should ignore low confidence matches"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a low confidence Walmart match"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "Original memo", payee_name: "WALMART.COM", amount: -5000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 50.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Test Product", price: 50.00, quantity: 1))
        
        def match = new TransactionMatch(ynabTx, order, "", 0.4, "Low confidence match")
        
        when: "processWalmartMatches is called"
        def result = processor.processWalmartMatches([match], mockYnabService, false)
        
        then: "no updates should be made"
        result.updated == 0
        result.low_confidence == 1
        0 * mockYnabService.updateTransactionMemo(_, _)
    }
    
    def "generateWalmartMemo should format single transaction correctly"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a single transaction and order"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "", payee_name: "WALMART.COM", amount: -5000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 50.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Test Product", price: 50.00, quantity: 1))
        
        when: "generateWalmartMemo is called via reflection"
        def method = TransactionProcessor.class.getDeclaredMethod(
            "generateWalmartMemo", 
            YNABTransaction.class, 
            WalmartOrder.class, 
            boolean.class, 
            int.class, 
            int.class
        )
        method.setAccessible(true)
        def memo = method.invoke(processor, ynabTx, order, false, 1, 1)
        
        then: "memo should be formatted correctly"
        memo == "Walmart Order: 123456 - Test Product"
    }
    
    def "generateWalmartMemo should format multi-transaction correctly"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a multi-transaction order"
        def ynabTx = new YNABTransaction(id: "tx1", memo: "", payee_name: "WALMART.COM", amount: -10000)
        def order = new WalmartOrder(
            orderId: "123456",
            orderDate: "2024-01-15",
            totalAmount: 150.00,
            orderStatus: "Delivered"
        )
        order.addItem(new WalmartOrderItem(title: "Product A", price: 100.00, quantity: 1))
        order.addItem(new WalmartOrderItem(title: "Product B", price: 50.00, quantity: 1))
        
        when: "generateWalmartMemo is called for charge 1 of 2"
        def method = TransactionProcessor.class.getDeclaredMethod(
            "generateWalmartMemo", 
            YNABTransaction.class, 
            WalmartOrder.class, 
            boolean.class, 
            int.class, 
            int.class
        )
        method.setAccessible(true)
        def memo = method.invoke(processor, ynabTx, order, true, 1, 2)
        
        then: "memo should include charge information"
        memo.contains("Walmart Order: 123456")
        memo.contains("Charge 1 of 2")
        memo.contains("2 items:")
    }
    
    def "isProcessed should detect Walmart Order in memo"() {
        given: "a processor instance"
        def processor = new TransactionProcessor(mockConfig)
        
        and: "a transaction with Walmart Order in memo"
        def transaction = new YNABTransaction(
            id: "tx1", 
            memo: "Walmart Order: 123456 - Test Product",
            payee_name: "WALMART.COM"
        )
        
        when: "getUnprocessedTransactions is called"
        def result = processor.getUnprocessedTransactions([transaction])
        
        then: "transaction should be filtered out as already processed"
        result.size() == 0
    }
}
