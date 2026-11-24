package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.TransactionMatch
import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.service.AmazonService
import com.ynab.amazon.service.WalmartService
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
    WalmartService mockWalmartService
    TransactionMatcher mockMatcher
    TransactionProcessor mockProcessor
    
    def setup() {
        // Spock stubs/mocks
        mockConfig = Stub(Configuration) {
            isValid() >> true
            isDryRun() >> false
            walmartEnabled >> false
        }
        mockYnabService = Mock(YNABService)
        mockAmazonService = Mock(AmazonService)
        mockWalmartService = Mock(WalmartService)
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
        mockProcessor.processAmazonTransactions(_ as List, _ as List, mockYnabService, false) >> stats
        
        when: "executeWorkflow is called"
        def method = YNABAmazonTransactionUpdater.class.getDeclaredMethod(
            "executeWorkflow", 
            YNABService.class, 
            AmazonService.class,
            WalmartService.class,
            TransactionMatcher.class, 
            TransactionProcessor.class,
            Configuration.class
        )
        method.setAccessible(true)
        method.invoke(null, mockYnabService, mockAmazonService, mockWalmartService, mockMatcher, mockProcessor, mockConfig)
        
        then: "all expected service methods should be called in the correct order"
        1 * mockYnabService.getTransactions()
        1 * mockAmazonService.getOrders()
        1 * mockProcessor.getUnprocessedTransactions(_ as List)
        1 * mockMatcher.findMatches(_ as List, _ as List)
        1 * mockProcessor.processAmazonTransactions(_ as List, _ as List, mockYnabService, false)
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
            walmartEnabled >> false
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
            WalmartService.class,
            TransactionMatcher.class, 
            TransactionProcessor.class,
            Configuration.class
        )
        method.setAccessible(true)
        method.invoke(null, mockYnabService, mockAmazonService, mockWalmartService, mockMatcher, mockProcessor, dryConfig)
        
        then: "updateTransactions should be called with dry run mode"
        1 * mockProcessor.processAmazonTransactions(_ as List, _ as List, mockYnabService, true)
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
        services.size() == 5
        services[0] instanceof YNABService
        services[1] instanceof AmazonService
        services[2] == null  // WalmartService is null when walmartEnabled is false
        services[3] instanceof TransactionMatcher
        services[4] instanceof TransactionProcessor
    }
    
    @spock.lang.TempDir
    File authTempDir

    def "authentication notices preserve memos remain retryable and disappear after successful augmentation"() {
        given:
        def config = authConfig()
        def runner = Stub(com.ynab.amazon.service.ProcessRunner) {
            run(_, _, _) >>> [bridgeResult(3), bridgeResult(3), new com.ynab.amazon.service.ProcessResult(
                com.ynab.amazon.service.ProcessResult.Status.SUCCESS, 0,
                '{"schema_version":1,"orders":[{"order_number":"recovered","order_placed_date":"2026-11-20","grand_total":10,"items":[{"title":"Recovered product","quantity":1}]}]}', '')]
        }
        def amazon = new AmazonService(config, new com.ynab.amazon.service.PythonAmazonOrderFetcher(config, runner))
        def processor = new TransactionProcessor(config)
        String original = 'Personal purchase for the upcoming family trip'
        def pending = authTransaction('pending', original)
        def blank = authTransaction('blank', null)
        blank.amount = -20000
        def excluded = [
            authTransaction('retailer', 'Groceries'),
            authTransaction('transfer', ''),
            authTransaction('deleted', ''),
            authTransaction('refund', ''),
            authTransaction('processed', 'items: Already augmented'),
            authTransaction('tracked', '')
        ]
        excluded[0].payee_name = 'WALMART'
        excluded[1].transfer_account_id = 'other-account'
        excluded[2].deleted = 'true'
        excluded[3].amount = 10000
        def transactions = [pending, blank] + excluded
        Map<String, List<String>> writes = [:].withDefault { [] }
        def ynab = Stub(YNABService) {
            getTransactions() >> transactions
            getBudgetAccounts() >> (['account'] as Set)
            getTransaction(_) >> { String id -> com.ynab.amazon.service.AmazonEnrichment.snapshot(transactions.find { it.id == id }) }
            putTransaction(_, _) >> { String id, Map changes ->
                writes[id] << changes.memo
                transactions.find { it.id == id }.memo = changes.memo
            }
            updateTransactionMemo(_, _) >> { String id, String memo -> writes[id] << memo; true }
        }

        when:
        authWorkflow(config, ynab, amazon, processor)
        authWorkflow(config, ynab, amazon, new TransactionProcessor(config))

        then:
        writes.keySet() == ['pending', 'blank'] as Set
        writes.pending == [original + ' | [ERROR: Amazon Login Required on ynab-amazon-transaction-updater]']
        writes.blank == ['[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]']
        pending.memo.length() > 100
        new TransactionProcessor(config).getUnprocessedTransactions([pending, blank])*.id == ['pending', 'blank']

        when:
        authWorkflow(config, ynab, amazon, new TransactionProcessor(config))

        then:
        writes.pending.size() == 2
        writes.pending.last().contains('Recovered product')
        writes.pending.last().contains(original)
        !writes.pending.last().contains('Login Required')
        new TransactionProcessor(config).getUnprocessedTransactions([pending]).empty
        writes.blank.size() == 1
    }

    def "generic failures empty history and dry run do not write auth notices"() {
        given:
        def config = authConfig()
        config.dryRun = dryRun
        def runner = Stub(com.ynab.amazon.service.ProcessRunner) { run(_, _, _) >> result }
        def amazon = new AmazonService(config, new com.ynab.amazon.service.PythonAmazonOrderFetcher(config, runner))
        def ynab = Mock(YNABService)
        ynab.getTransactions() >> [authTransaction('pending', 'Keep me')]

        when:
        authWorkflow(config, ynab, amazon, new TransactionProcessor(config))

        then:
        0 * ynab.updateTransactionMemo(_, _)

        where:
        result || dryRun
        bridgeResult(1) || false
        new com.ynab.amazon.service.ProcessResult(com.ynab.amazon.service.ProcessResult.Status.TIMED_OUT, 3, '', '') || false
        new com.ynab.amazon.service.ProcessResult(com.ynab.amazon.service.ProcessResult.Status.SUCCESS, 0, '{"schema_version":1,"orders":[]}', '') || false
        bridgeResult(3) || true
    }

    def "CSV augmentation during auth failure is not overwritten with a login notice"() {
        given:
        def config = authConfig()
        File csv = new File(authTempDir, 'orders.csv')
        csv.text = 'Order ID,Order Date,Title,Price,Quantity\ncsv-order,2026-11-20,CSV product,-10,1\n'
        config.amazonCsvFilePath = csv.absolutePath
        def runner = Stub(com.ynab.amazon.service.ProcessRunner) { run(_, _, _) >> bridgeResult(3) }
        def amazon = new AmazonService(config, new com.ynab.amazon.service.PythonAmazonOrderFetcher(config, runner))
        def pending = authTransaction('pending', '')
        List<String> writes = []
        def ynab = Stub(YNABService) {
            getTransactions() >> [pending]
            getBudgetAccounts() >> (['account'] as Set)
            getTransaction(_) >> { String id -> com.ynab.amazon.service.AmazonEnrichment.snapshot(pending) }
            putTransaction(_, _) >> { String id, Map changes -> writes << changes.memo; pending.memo = changes.memo }
            updateTransactionMemo(_, _) >> { String id, String memo -> writes << memo; true }
        }

        when:
        authWorkflow(config, ynab, amazon, new TransactionProcessor(config))

        then:
        writes.size() == 1
        writes[0].contains('CSV product')
        !writes[0].contains('Login Required')
    }

    private Configuration authConfig() {
        File processed = new File(authTempDir, 'processed.json')
        processed.text = '{"processed_transaction_ids":["tracked"]}'
        new Configuration(processedTransactionsFile: processed.absolutePath,ynabBudgetId:'plan',amazonJournalFile:new File(authTempDir,'amazon.json').path,
            amazonPythonExecutable: 'python', amazonPythonBridgeScript: 'bridge.py', amazonPythonConfigPath: 'upstream.yml',
            amazonPythonTimeoutSeconds: 2, amazonPythonMaxOutputBytes: 4096, walmartEnabled: false)
    }

    private static YNABTransaction authTransaction(String id, String memo) {
        new YNABTransaction(id: id, memo: memo, date: '2026-11-20', payee_name: 'AMAZON.COM', amount: -10000,
            account_id:'account',approved:'false',deleted:'false',cleared:'cleared')
    }

    private static com.ynab.amazon.service.ProcessResult bridgeResult(int code) {
        new com.ynab.amazon.service.ProcessResult(com.ynab.amazon.service.ProcessResult.Status.NON_ZERO_EXIT, code, '', '')
    }

    private static void authWorkflow(Configuration config, YNABService ynab, AmazonService amazon, TransactionProcessor processor) {
        def method = YNABAmazonTransactionUpdater.getDeclaredMethod('executeWorkflow',
            YNABService, AmazonService, WalmartService, TransactionMatcher, TransactionProcessor, Configuration)
        method.accessible = true
        method.invoke(null, ynab, amazon, null, new TransactionMatcher(), processor, config)
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
