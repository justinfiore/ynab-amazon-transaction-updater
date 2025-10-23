package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.service.AmazonService
import com.ynab.amazon.service.TransactionMatcher
import com.ynab.amazon.service.TransactionProcessor
import com.ynab.amazon.service.YNABService
import spock.lang.Specification

/**
 * Test class for the main YNABAmazonTransactionUpdater class
 */
class YNABAmazonTransactionUpdater_UT extends Specification {

    // Mocked dependencies
    Configuration mockConfig
    YNABService mockYnabService
    AmazonService mockAmazonService
    TransactionMatcher mockMatcher
    TransactionProcessor mockProcessor
    
    def setup() {
        // Spock stubs/mocks
        mockConfig = Stub(Configuration) {
            isValid() >> true
            isDryRun() >> false
        }
        mockYnabService = Mock(YNABService)
        mockAmazonService = Mock(AmazonService)
        mockMatcher = Mock(TransactionMatcher)
        mockProcessor = Mock(TransactionProcessor)
    }
    
    def "executeWorkflow should orchestrate the main application flow correctly"() {
        given: "sample transactions and orders"
        def ynabTransactions = createSampleYnabTransactions()
        def amazonOrders = createSampleAmazonOrders()
        def unprocessedTransactions = ynabTransactions.take(2)
        def matches = [
            new TransactionMatch(
                ynabTransaction: ynabTransactions[0],
                amazonOrder: amazonOrders[0],
                confidenceScore: 0.9,
                proposedMemo: "Updated memo 1"
            )
        ]
        def stats = [
            updated: 1,
            high_confidence: 1,
            medium_confidence: 0,
            low_confidence: 0
        ]
        
        // Configure mock interactions
        mockYnabService.getTransactions() >> ynabTransactions
        mockAmazonService.getOrders() >> amazonOrders
        mockProcessor.getUnprocessedTransactions(_ as List) >> unprocessedTransactions
        mockMatcher.findMatches(_ as List, _ as List) >> matches
        mockProcessor.updateTransactions(_ as List, mockYnabService, false) >> stats
        
        when: "executeWorkflow is called"
        def method = YNABAmazonTransactionUpdater.class.getDeclaredMethod(
            "executeWorkflow", 
            YNABService.class, 
            AmazonService.class, 
            TransactionMatcher.class, 
            TransactionProcessor.class,
            Configuration.class
        )
        method.setAccessible(true)
        method.invoke(null, mockYnabService, mockAmazonService, mockMatcher, mockProcessor, mockConfig)
        
        then: "all expected service methods should be called in the correct order"
        1 * mockYnabService.getTransactions()
        1 * mockAmazonService.getOrders()
        1 * mockProcessor.getUnprocessedTransactions(_ as List)
        1 * mockMatcher.findMatches(_ as List, _ as List)
        1 * mockProcessor.updateTransactions(_ as List, mockYnabService, false)
    }
    
    def "executeWorkflow should use dry run mode when configured"() {
        given: "sample data and dry run mode enabled"
        def ynabTransactions = createSampleYnabTransactions()
        def amazonOrders = createSampleAmazonOrders()
        def unprocessedTransactions = ynabTransactions.take(2)
        def matches = [new TransactionMatch(
            ynabTransaction: ynabTransactions[0],
            amazonOrder: amazonOrders[0],
            confidenceScore: 0.9,
            proposedMemo: "Updated memo 1"
        )]
        
        // Configure mocks
        def dryConfig = Stub(Configuration) {
            isValid() >> true
            isDryRun() >> true
        }
        mockYnabService.getTransactions() >> ynabTransactions
        mockAmazonService.getOrders() >> amazonOrders
        mockProcessor.getUnprocessedTransactions(_ as List) >> unprocessedTransactions
        mockMatcher.findMatches(_ as List, _ as List) >> matches
        
        when: "executeWorkflow is called"
        def method = YNABAmazonTransactionUpdater.class.getDeclaredMethod(
            "executeWorkflow", 
            YNABService.class, 
            AmazonService.class, 
            TransactionMatcher.class, 
            TransactionProcessor.class,
            Configuration.class
        )
        method.setAccessible(true)
        method.invoke(null, mockYnabService, mockAmazonService, mockMatcher, mockProcessor, dryConfig)
        
        then: "updateTransactions should be called with dry run mode"
        1 * mockProcessor.updateTransactions(_ as List, mockYnabService, true)
    }
    
    def "main should exit when configuration is invalid"() {
        given: "an invalid configuration"
        def badConfig = Stub(Configuration) {
            isValid() >> false
        }
        
        when: "main is called"
        // Use a modified version of main for testing
        def method = YNABAmazonTransactionUpdater.class.getDeclaredMethod("validateConfig", Configuration.class)
        method.setAccessible(true)
        method.invoke(null, badConfig)
        
        then: "System.exit should be called with status 1"
        // We can't actually test System.exit, but we can test that an exception is thrown when config is invalid
        def exception = thrown(Exception)
        exception.getCause() instanceof IllegalStateException
    }
    
    def "main should initialize all services correctly"() {
        given: "a valid configuration"
        // Configuration mock is already set up in setup()
        
        when: "initializeServices is called"
        def method = YNABAmazonTransactionUpdater.class.getDeclaredMethod("initializeServices", Configuration.class)
        method.setAccessible(true)
        def services = method.invoke(null, mockConfig)
        
        then: "all services should be initialized correctly"
        services.size() == 4
        services[0] instanceof YNABService
        services[1] instanceof AmazonService
        services[2] instanceof TransactionMatcher
        services[3] instanceof TransactionProcessor
    }
    
    // Helper methods to create test data
    private List<YNABTransaction> createSampleYnabTransactions() {
        return [
            new YNABTransaction(
                id: "tx1",
                date: "2023-05-15",
                amount: 25990,  // 25.99 in milliunits
                payee_name: "AMAZON.COM",
                memo: "Original memo 1",
                cleared: "cleared",
                approved: "true"
            ),
            new YNABTransaction(
                id: "tx2",
                date: "2023-05-20",
                amount: 49990,  // 49.99 in milliunits
                payee_name: "AMAZON MARKETPLACE",
                memo: "Original memo 2",
                cleared: "cleared",
                approved: "true"
            ),
            new YNABTransaction(
                id: "tx3",
                date: "2023-05-25",
                amount: 35990,  // 35.99 in milliunits
                payee_name: "WALMART",  // Not Amazon
                memo: "Original memo 3",
                cleared: "cleared",
                approved: "true"
            )
        ]
    }
    
    private List<AmazonOrder> createSampleAmazonOrders() {
        def order1 = new AmazonOrder(
            orderId: "123-4567890-1234567",
            orderDate: "2023-05-15",
            totalAmount: 25.99
        )
        order1.addItem(new AmazonOrderItem(title: "Wireless Headphones", price: 25.99, quantity: 1))
        
        def order2 = new AmazonOrder(
            orderId: "123-4567890-2345678",
            orderDate: "2023-05-20",
            totalAmount: 49.99
        )
        order2.addItem(new AmazonOrderItem(title: "Kindle Paperwhite", price: 49.99, quantity: 1))
        
        return [order1, order2]
    }
}
