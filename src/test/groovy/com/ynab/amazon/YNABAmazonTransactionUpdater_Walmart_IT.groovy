package com.ynab.amazon

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import com.ynab.amazon.service.*
import spock.lang.Specification
import spock.lang.TempDir

class YNABAmazonTransactionUpdater_Walmart_IT extends Specification {
    @TempDir File directory

    def 'workflow previews both retailers without legacy completion heuristics hiding Walmart'() {
        given:
        def config = new Configuration(walmartEnabled:true,dryRun:true,walmartJournalFile:new File(directory,'journal.json').path)
        def ynab = Stub(YNABService) { getTransactions() >> WalmartFixture.transactions(); getWalmartPlanCurrency() >> 'USD' }
        def amazon = Stub(AmazonService) { getOrders() >> []; isLoginRequired() >> false }
        def walmart = Stub(WalmartService) { getOrders() >> [WalmartFixture.order()] }
        def method = YNABAmazonTransactionUpdater.getDeclaredMethod('executeWorkflow',YNABService,AmazonService,WalmartService,TransactionMatcher,TransactionProcessor,Configuration)
        method.accessible=true

        when:
        method.invoke(null,ynab,amazon,walmart,new TransactionMatcher(),new TransactionProcessor(config),config)

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
        def method = YNABAmazonTransactionUpdater.getDeclaredMethod('executeWorkflow',YNABService,AmazonService,WalmartService,TransactionMatcher,TransactionProcessor,Configuration)
        method.accessible=true

        when:
        method.invoke(null,ynab,amazon,walmart,new TransactionMatcher(),processor,config)

        then:
        noExceptionThrown()
        processor.getUnprocessedTransactions([tx]).size()==1
    }
}
