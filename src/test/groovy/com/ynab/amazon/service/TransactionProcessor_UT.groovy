package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.model.YNABTransaction
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
}
