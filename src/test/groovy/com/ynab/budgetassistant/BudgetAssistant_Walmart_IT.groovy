package com.ynab.budgetassistant

import com.ynab.budgetassistant.config.Configuration
import com.ynab.budgetassistant.model.*
import com.ynab.budgetassistant.service.*
import spock.lang.Specification
import spock.lang.TempDir

class BudgetAssistant_Walmart_IT extends Specification {
    @TempDir File directory

    def 'workflow previews both retailers without legacy completion heuristics hiding Walmart'() {
        given:
        def config = new Configuration(walmartEnabled:true,dryRun:true,walmartJournalFile:new File(directory,'journal.json').path)
        def ynab = Stub(YNABService) { getTransactions() >> WalmartFixture.transactions(); getWalmartPlanCurrency() >> 'USD' }
        def amazon = Stub(AmazonService) { getOrders() >> []; isLoginRequired() >> false }
        def walmart = Stub(WalmartService) { getOrders() >> [WalmartFixture.order()] }
        def method = BudgetAssistant.getDeclaredMethod('executeWorkflow',YNABService,AmazonService,WalmartService,AmazonTransactionMatcher,TransactionProcessor,Configuration)
        method.accessible=true

        when:
        method.invoke(null,ynab,amazon,walmart,new AmazonTransactionMatcher(),new TransactionProcessor(config),config)

        then:
        noExceptionThrown()
        !new File(config.walmartJournalFile).exists()
    }

    def 'Walmart fetch failure does not suppress Amazon processing'() {
        given:
        def config = new Configuration(walmartEnabled:true,dryRun:true)
        def tx = new YNABTransaction(id:'amazon',date:'2026-11-20',amount:-20000,payee_name:'AMAZON')
        def ynab = Stub(YNABService) { getTransactions() >> [tx]; getWalmartPlanCurrency() >> 'USD' }
        def amazon = Stub(AmazonService) { getOrders() >> [new AmazonOrder(orderId:'A',orderDate:'2026-11-20',totalAmount:-20)]; isLoginRequired() >> false }
        def walmart = Stub(WalmartService) { getOrders() >> { throw new IllegalStateException('fixed failure') } }
        def processor = new TransactionProcessor(config)
        def method = BudgetAssistant.getDeclaredMethod('executeWorkflow',YNABService,AmazonService,WalmartService,AmazonTransactionMatcher,TransactionProcessor,Configuration)
        method.accessible=true

        when:
        method.invoke(null,ynab,amazon,walmart,new AmazonTransactionMatcher(),processor,config)

        then:
        noExceptionThrown()
        processor.getUnprocessedTransactions([tx]).size()==1
    }

    def 'Sheetz is disabled by default and does not access its source or write transactions'() {
        given:
        def config = new Configuration()
        def ynab = Mock(YNABService)
        def amazon = Stub(AmazonService) { getOrders() >> []; isLoginRequired() >> false }
        def processor = Stub(TransactionProcessor)
        def matcher = Stub(AmazonTransactionMatcher)
        def method = BudgetAssistant.getDeclaredMethod('executeWorkflow', YNABService, AmazonService,
            WalmartService, AmazonTransactionMatcher, TransactionProcessor, Configuration)
        method.accessible = true

        when:
        method.invoke(null, ynab, amazon, null, matcher, processor, config)

        then:
        !config.sheetzEnabled
        1 * ynab.getTransactions() >> []
        0 * ynab.getPlanCurrency()
        0 * ynab.getBudgetAccounts()
        0 * ynab.putTransaction(_, _)
    }

    def 'Sheetz source failure cannot trigger fallback and does not stop other retailers'() {
        given:
        def bridge = new File(directory, 'bridge.sh')
        bridge.text = "#!/bin/sh\necho ${failure} >&2\nexit 1\n"
        def config = new Configuration(sheetzEnabled:true, sheetzMemoWrites:true, sheetzCategoryWrites:true,
            sheetzAccounts:['account'], sheetzPythonExecutable:'sh', sheetzBridgeScript:bridge.path,
            sheetzCredentialsFile:'credentials', sheetzTokenFile:'token', walmartEnabled:true, dryRun:false)
        def transaction = new YNABTransaction(id:'sheetz', date:'2026-11-20', amount:-10000, payee_name:'SHEETZ',
            account_id:'account', approved:'false', deleted:'false', cleared:'uncleared')
        def ynab = Mock(YNABService)
        def amazon = Stub(AmazonService) { getOrders() >> []; isLoginRequired() >> false }
        def walmart = Mock(WalmartService)
        def processor = Mock(TransactionProcessor)
        def matcher = Stub(AmazonTransactionMatcher)
        def method = BudgetAssistant.getDeclaredMethod('executeWorkflow', YNABService, AmazonService,
            WalmartService, AmazonTransactionMatcher, TransactionProcessor, Configuration)
        method.accessible = true

        when:
        method.invoke(null, ynab, amazon, walmart, matcher, processor, config)

        then:
        1 * ynab.getTransactions() >> [transaction]
        1 * ynab.getPlanCurrency() >> 'USD'
        1 * ynab.getBudgetAccounts() >> (['account'] as Set)
        1 * ynab.getWalmartPlanCurrency() >> 'USD'
        1 * walmart.getOrders() >> [WalmartFixture.order()]
        1 * processor.processAmazonTransactions(_, _, ynab, false) >> [:]
        1 * processor.processWalmartMatches(_, ynab, false) >> [:]
        0 * ynab.putTransaction(_, _)
        0 * ynab.getTransaction(_)

        where:
        failure << ['signin_challenge', 'receipt_403']
    }
}
