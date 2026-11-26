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
    
    def "notices are batched with approval and only saved IDs update local memos"() {
        given:
        def processor = new TransactionProcessor(mockConfig)
        def service = Mock(YNABService)
        def transactions = [new YNABTransaction(id: 'a', memo: 'Original', approved: 'true'),
                            new YNABTransaction(id: 'b', memo: null, approved: 'false'),
                            new YNABTransaction(id: 'c', memo: 'null', approved: null),
                            new YNABTransaction(id: 'already', memo: AmazonAuthNotice.MESSAGE),
                            new YNABTransaction(id: 'processed', memo: 'items: product')]

        when:
        processor.addAmazonLoginNotices(transactions, service, false)

        then:
        1 * service.updateTransactionMemos([
            [id: 'a', memo: "Original | ${AmazonAuthNotice.MESSAGE}".toString(), approved: true],
            [id: 'b', memo: AmazonAuthNotice.MESSAGE, approved: false]
        ]) >> (['a'] as Set)
        0 * service.updateTransactionMemo(_, _)
        transactions*.memo == ["Original | ${AmazonAuthNotice.MESSAGE}".toString(), null,
                               'null', AmazonAuthNotice.MESSAGE, 'items: product']
        processor.getUnprocessedTransactions(transactions)*.id == ['a', 'b', 'c', 'already']
        new TransactionProcessor(mockConfig).getUnprocessedTransactions(transactions)*.id == ['a', 'b', 'c', 'already']
    }

    def "batch failure does not update memos or replay requests"() {
        given:
        def processor = new TransactionProcessor(mockConfig)
        def service = Mock(YNABService)
        def transactions = [new YNABTransaction(id: 'a', memo: 'Original', approved: 'true'), new YNABTransaction(id: 'b', approved: 'false')]

        when:
        processor.addAmazonLoginNotices(transactions, service, false)

        then:
        1 * service.updateTransactionMemos(_) >> { if (throwsFailure) throw new IOException('unavailable'); return [] as Set }
        0 * service.updateTransactionMemo(_, _)
        transactions*.memo == ['Original', null]
        processor.getUnprocessedTransactions(transactions)*.id == ['a', 'b']

        where:
        throwsFailure << [false, true]
    }

    def "dry run and no eligible notices make no batch call"() {
        given:
        def processor = new TransactionProcessor(mockConfig)
        def service = Mock(YNABService)
        def transactions = [new YNABTransaction(id: 'a', memo: 'Original')]

        when:
        processor.addAmazonLoginNotices(transactions, service, true)
        processor.addAmazonLoginNotices([new YNABTransaction(id: 'b', memo: AmazonAuthNotice.MESSAGE)], service, false)

        then:
        0 * service.updateTransactionMemos(_)
        0 * service.updateTransactionMemo(_, _)
        transactions[0].memo == 'Original'
    }

    def "notice respects 500 character memo limit"() {
        given:
        def processor = new TransactionProcessor(mockConfig) {
            List<YNABTransaction> getUnprocessedTransactions(List<YNABTransaction> all) { all }
        }
        def service = Mock(YNABService)
        int limit = 500 - 3 - AmazonAuthNotice.MESSAGE.length()
        def transactions = [new YNABTransaction(id: 'fits', memo: 'x' * limit, approved: 'false'),
                            new YNABTransaction(id: 'long', memo: 'x' * (limit + 1), approved: 'false')]

        when:
        processor.addAmazonLoginNotices(transactions, service, false)

        then:
        1 * service.updateTransactionMemos({ it.size() == 1 && it[0].id == 'fits' && it[0].memo.length() == 500 }) >> (['fits'] as Set)
        transactions[0].memo.length() == 500
        transactions[1].memo == 'x' * (limit + 1)
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
        mockYnabService.updateTransactionMemo("tx1", "🤖 Updated memo") >> true
        
        when: "updateTransactions is called in normal mode"
        def result = processor.updateTransactions([match], mockYnabService, false)
        
        then: "transactions should be updated"
        result.updated == 1
        result.high_confidence == 1
    }

    def 'Amazon marker is idempotent and does not truncate a full memo'() {
        given:
        def processor = new TransactionProcessor(mockConfig)
        def order = new AmazonOrder(orderId: 'order1')
        def matches = [
            new TransactionMatch(ynabTransaction: new YNABTransaction(id:'marked'), amazonOrder:order, proposedMemo:'🤖 Existing', confidenceScore:0.9),
            new TransactionMatch(ynabTransaction: new YNABTransaction(id:'long'), amazonOrder:order, proposedMemo:'x' * 498, confidenceScore:0.9)
        ]

        when:
        def result = processor.updateTransactions(matches, mockYnabService, false)

        then:
        1 * mockYnabService.updateTransactionMemo('marked', '🤖 Existing') >> true
        0 * mockYnabService.updateTransactionMemo('long', _)
        result.updated == 1
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
    
    def 'Walmart preview preserves user memo and does not mark completion'() {
        given:
        def config = new Configuration(walmartJournalFile: new File(tempFolder, 'walmart.json').path)
        def tx = new YNABTransaction(id: 'w1', date: '2026-11-20', amount: -70000, memo: 'User note', payee_name: 'WALMART')
        def order = new WalmartOrder(orderId: '123', orderDate: '2026-11-20', totalAmount: 100.00, currency: 'USD',
            orderUrl: 'https://www.walmart.com/orders/123',
            charges: [[key:'c1',date:'2026-11-20',cents:7000L,currency:'USD',payment:'CREDITCARD:VISA:1234']])
        def matches = new TransactionMatcher().findWalmartMatches([tx], [order])

        when:
        def result = new TransactionProcessor(config).processWalmartMatches(matches, mockYnabService, true)

        then:
        result.updated == 0
        result.preview[0].operations[0].memo == 'User note | 2026-11-20 | Total $100.00 | #123 | https://www.walmart.com/orders/123'
        !new File(config.walmartJournalFile).exists()
        1 * mockYnabService.getWalmartPlanCurrency() >> 'USD'
        0 * mockYnabService._
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
