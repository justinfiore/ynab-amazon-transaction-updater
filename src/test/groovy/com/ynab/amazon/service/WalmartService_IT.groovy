package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import com.ynab.amazon.YNABAmazonTransactionUpdater
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/** Actual HTTP contract tests; account-free and independent of Docker/models. */
class WalmartService_IT extends Specification {
    @TempDir File directory
    def json = new ObjectMapper()
    HttpServer server
    Map<String, Map> remote
    List<Map> writes
    Configuration config
    YNABService ynab
    boolean loseResponse
    boolean failReadback
    boolean pendingReadback
    boolean rejectSmall
    boolean stopBeforeSmallIntent
    List<Map> categoryGroups
    String planCurrency = 'USD'

    def setup() {
        remote = [:]; writes = []
        categoryGroups = [[name:'Everyday',categories:[[id:'g',name:'Groceries'],[id:'h',name:'Household']]]]
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/plans/plan') { exchange ->
            String suffix = exchange.requestURI.path - '/v1/plans/plan'
            Map body
            if (suffix == '') {
                if (planCurrency=='unavailable') {
                    exchange.sendResponseHeaders(503,-1); exchange.close(); return
                }
                body = [data:[plan:planCurrency ? [currency_format:[iso_code:planCurrency]] : [:]]]
            }
            else if (suffix == '/categories') body = [data:[category_groups:categoryGroups]]
            else if (suffix == '/accounts') body = [data:[accounts:[[id:'account',on_budget:true,deleted:false,closed:false]]]]
            else {
                String id = suffix.tokenize('/').last()
                if (stopBeforeSmallIntent && exchange.requestMethod=='GET' && id=='small' && writes.size()==1) {
                    exchange.sendResponseHeaders(503,-1); exchange.close(); return
                }
                if ((exchange.requestMethod=='GET' && pendingReadback) || (exchange.requestMethod=='PUT' && rejectSmall && id=='small')) {
                    pendingReadback=false; exchange.sendResponseHeaders(503,-1); exchange.close(); return
                }
                if (exchange.requestMethod == 'PUT') {
                    Map change = json.readValue(exchange.requestBody, Map).transaction
                    writes << [id:id,change:json.readValue(json.writeValueAsBytes(change),Map)]
                    remote[id] += change
                    remote[id].subtransactions?.eachWithIndex { row,index -> row.id = "${id}-row-${index}"; row.deleted=false }
                    if (failReadback) { failReadback=false; pendingReadback=true }
                    if (loseResponse) { loseResponse=false; exchange.close(); return }
                }
                body = [data:[transaction:remote[id]]]
            }
            byte[] bytes = json.writeValueAsBytes(body)
            exchange.responseHeaders.set('Content-Type','application/json')
            exchange.sendResponseHeaders(200,bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
            exchange.close()
        }
        server.start()
        config = new Configuration(ynabBudgetId:'plan',ynabApiKey:'fixture',ynabBaseUrl:"http://127.0.0.1:${server.address.port}/v1",
            walmartEnrichment:'exception_first',walmartDefaultCategoryId:'g',walmartCategorization:[categories:[[id:'g'],[id:'h']]],
            walmartJournalFile:new File(directory,'journal.json').path)
        ynab = new YNABService(config)
    }
    def cleanup() { ynab.close(); server.stop(0) }

    @Unroll
    def 'Walmart #mode requires verified USD plan currency: #currency'() {
        given:
        planCurrency=currency
        config.walmartEnabled=true
        config.walmartEnrichment=mode
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        server.createContext('/v1/budgets/plan/transactions') { exchange ->
            byte[] bytes=json.writeValueAsBytes([data:[transactions:remote.values()]])
            exchange.sendResponseHeaders(200,bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
            exchange.close()
        }
        def amazon=Stub(AmazonService) { getOrders() >> []; isLoginRequired() >> false }
        def walmart=Stub(WalmartService) { getOrders() >> [WalmartFixture.order()] }
        def method=YNABAmazonTransactionUpdater.getDeclaredMethod('executeWorkflow',YNABService,AmazonService,WalmartService,TransactionMatcher,TransactionProcessor,Configuration)
        method.accessible=true

        when:
        method.invoke(null,ynab,amazon,walmart,new TransactionMatcher(),new TransactionProcessor(config),config)

        then:
        writes.size()==(currency=='USD' ? 2 : 0)
        currency=='USD' || !new File(config.walmartJournalFile).exists()

        when: 'direct enrichment also refuses a precomputed match without supported currency'
        if (currency!='USD') {
            def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])
            new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        }

        then:
        writes.size()==(currency=='USD' ? 2 : 0)
        currency=='USD' || remote.values().every { !it.memo?.contains('walmart.com') && !it.category_id && !it.subtransactions }

        where:
        [currency, mode] << [['CAD',null,'unavailable','USD'],['link_only','exception_first']].combinations()
    }

    def 'API internal flags exclude special categories in setup and runtime without reserving display names'() {
        given:
        categoryGroups = [
            [name:'Everyday',internal:false,categories:[[id:'internal-category',name:'Internal category',internal:true]]],
            [name:'Renamed system group',internal:true,categories:[[id:'internal-group',name:'Payment',internal:false]]],
            [name:'Credit Card Payments',internal:false,categories:[[id:'ordinary',name:'User category',internal:false]]]
        ]
        File file = new File(directory,'setup.yml'); file.text='walmart: {enabled: false}\n'
        def output = new StringWriter()

        when:
        def catalog = ynab.getWalmartCategories()
        boolean saved = WalmartOnboarding.selectCategories(file.toPath(),ynab,
            new BufferedReader(new StringReader('1\n1\nyes\n')),new PrintWriter(output,true))

        then:
        saved
        output.toString().contains('Credit Card Payments / User category')
        !output.toString().contains('Internal category')
        !output.toString().contains('Renamed system group')
        WalmartAllocation.categories(catalog,[[id:'ordinary']],'ordinary').keySet()==['ordinary'] as Set

        when:
        WalmartAllocation.categories(catalog,[[id:'internal-category']],'internal-category')

        then:
        thrown(IllegalStateException)

        when:
        WalmartAllocation.categories(catalog,[[id:'internal-group']],'internal-group')

        then:
        thrown(IllegalStateException)
    }

    def 'HTTP writes preserve parents and remain unapproved; lost response and restart do not duplicate allocations'() {
        given:
        def order = WalmartFixture.order()
        def transactions = WalmartFixture.transactions()
        assert ynab.getWalmartBudgetAccounts() == ['account'] as Set
        assert transactions.every { WalmartAllocation.eligible(it, ['account'] as Set) }
        transactions.each { remote[it.id] = WalmartFixture.remote(it) }
        // Only approved product mappings authorize default/household classification.
        new WalmartJournal(config.walmartJournalFile).locked(false) { state, save ->
            state.mappings = ['plan/food':['g'], 'plan/soap':['h']]; save(state)
        }
        def matches = new TransactionMatcher().findWalmartMatches(transactions,[order])
        loseResponse = true

        when:
        def stats = new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        def again = new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        stats.updated == 2
        again.updated == 0
        writes.size() == 2
        writes[0].id == 'large'
        writes[0].change == [memo:matches.find { it.ynabTransaction.id=='large' }.proposedMemo,approved:false,category_id:null,
            subtransactions:[[amount:-20000,category_id:'h',memo:'Soap'],[amount:-50000,category_id:'g',memo:'Default Walmart Category']]]
        writes[1].change.category_id == 'g'
        !writes[1].change.containsKey('subtransactions')
        remote.large.amount == -70000
        remote.small.amount == -30000
        remote.values().every { !it.approved && it.account_id=='account' && it.cleared=='cleared' && it.import_id=='bank-import' }
        remote.large.memo.startsWith('User note | 2026-11-20 | Total $100.00 | #123 | ')
        !remote.large.memo.contains('Soap')
    }

    def 'preview performs no writes and leaves journal unchanged'() {
        given:
        def matches = new TransactionMatcher().findWalmartMatches(WalmartFixture.transactions(),[WalmartFixture.order()])

        when:
        def preview = new TransactionProcessor(config).processWalmartMatches(matches,ynab,true)

        then:
        writes.empty
        !new File(config.walmartJournalFile).exists()
        preview.preview[0].operations.size()==2
        preview.updated==0
    }

    def 'human change between snapshot and fresh read stops allocation for whole order'() {
        given:
        def txs = WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        remote.small.approved=true
        def matches = new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])

        when:
        def result = new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        writes.empty
        result.preview*.reason.contains('parent_changed')
        remote.small.approved
    }

    def 'restart resolves durable unknown intent before any retry and source drift stops remaining writes'() {
        given:
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[order])
        failReadback=true

        when:
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        thrown(IllegalStateException)
        writes.size()==1

        when:
        order.adjustments << [kind:'tip',cents:100L]
        def drift=new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        drift.preview*.reason==['applied_plan_drift']
        writes.size()==1

        when:
        order.adjustments.removeLast()
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        writes*.id==['large','small']
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> state.plans['plan/123'].operations.values().every { it.allocation && !it.pending } }
    }

    def 'restart never rebases an unattempted payment over human changes'() {
        given:
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        stopBeforeSmallIntent=true

        when:
        new TransactionProcessor(config).processWalmartMatches(new TransactionMatcher().findWalmartMatches(txs,[order]),ynab,false)

        then:
        thrown(IllegalStateException)
        writes*.id==['large']
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> state.plans['plan/123'].operations.large.allocation && !state.plans['plan/123'].operations.small }

        when:
        stopBeforeSmallIntent=false
        remote.small.memo='Human note'
        remote.small.category_id='h'
        def refreshed=WalmartFixture.transactions()
        refreshed.each { it.memo=remote[it.id].memo; it.category_id=remote[it.id].category_id; it.subtransactions=remote[it.id].subtransactions }
        def result=new TransactionProcessor(config).processWalmartMatches(new TransactionMatcher().findWalmartMatches(refreshed,[order]),ynab,false)

        then:
        result.preview*.reason.contains('parent_changed')
        writes*.id==['large']
        remote.small.memo=='Human note'
        remote.small.category_id=='h'
    }

    def 'a link-only run does not prevent later allocation with freshly fetched parents'() {
        given:
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        config.walmartEnrichment='link_only'
        def order=WalmartFixture.order()
        new TransactionProcessor(config).processWalmartMatches(new TransactionMatcher().findWalmartMatches(txs,[order]),ynab,false)

        when:
        config.walmartEnrichment='exception_first'
        txs.each { it.memo=remote[it.id].memo }
        new TransactionProcessor(config).processWalmartMatches(new TransactionMatcher().findWalmartMatches(txs,[order]),ynab,false)

        then:
        writes.size()==4
        writes.take(2).every { it.change.keySet()==['memo'] as Set }
        writes.drop(2).every { it.change.approved==false }
        remote.large.subtransactions.empty
        remote.small.subtransactions*.category_id==[null,null]
    }

    def 'approved decisions outside the shortlist retain conflicts without authorizing reuse'() {
        given:
        def purchase = { String id ->
            def order = new WalmartOrder(orderId:id,orderDate:'2026-11-20',currency:'USD',complete:true,totalAmount:10.00,
                orderUrl:"https://www.walmart.com/orders/details?orderId=${id}",
                items:[new WalmartOrderItem(itemId:'item',productId:'soap',title:'Soap',quantity:1,grossCents:1000L)],
                charges:[[key:'charge',date:'2026-11-21',payment:'VISA',currency:'USD',cents:1000L]])
            def tx = new YNABTransaction(id:id,date:'2026-11-21',amount:-10000L,payee_name:'WALMART',payee_id:'payee',
                account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
            remote[id] = WalmartFixture.remote(tx)
            new TransactionMatcher().findWalmartMatches([tx],[order])
        }
        def first = purchase('101')
        new TransactionProcessor(config).processWalmartMatches(first,ynab,false)
        remote['101'].approved=true
        remote['101'].category_id='h'
        def second = purchase('102')
        new TransactionProcessor(config).processWalmartMatches(second,ynab,false)
        assert remote['102'].category_id=='h'
        remote['102'].approved=true
        remote['102'].category_id='outside-shortlist'
        def before = json.readValue(json.writeValueAsBytes(remote),Map)

        when:
        new TransactionProcessor(config).processWalmartMatches(purchase('103'),ynab,false)

        then:
        remote['103'].category_id==null
        remote['101']==before['101']
        remote['102']==before['102']
        writes.size()==3
        config.walmartCategorization.categories*.id==['g','h']
    }

    def 'only complete approved identifiable fragments teach products and corrections replace stale evidence'() {
        given:
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[order])
        // No mappings: the $80 item fragments across $70/$30; all fragments must be approved.
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        remote.large.approved=true
        remote.large.category_id='h'

        when:
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> !state.mappings['plan/food'] }

        when:
        remote.small.approved=true
        remote.small.subtransactions[0].category_id='h'
        remote.small.subtransactions[1].category_id='g'
        remote.small.subtransactions=remote.small.subtransactions.reverse()
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> state.mappings['plan/food']==['h'] && state.mappings['plan/soap']==['g'] }
        writes.size()==2

        when:
        remote.large.category_id='g'
        remote.small.subtransactions.find { it.memo=='Apples' }.category_id='g'
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> state.mappings['plan/food']==['g'] }

        when:
        remote.small.subtransactions.find { it.memo=='Apples' }.amount=-9990
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> !state.mappings['plan/food'] }
        writes.size()==2
    }
}

class WalmartFixture {
    static WalmartOrder order() {
        new WalmartOrder(orderId:'123',orderDate:'2026-11-20',orderStatus:'Delivered',currency:'USD',complete:true,totalAmount:100.00,
            orderUrl:'https://www.walmart.com/orders/details?orderId=123',
            items:[new WalmartOrderItem(itemId:'a',productId:'food',title:'Apples',quantity:1.25,grossCents:8000L),
                new WalmartOrderItem(itemId:'b',productId:'soap',title:'Soap',quantity:2,grossCents:2000L)],
            adjustments:[[kind:'tax',cents:500L],[kind:'fee',cents:300L],[kind:'tip',cents:200L],[kind:'discount',cents:1000L]],
            charges:[[key:'c1',date:'2026-11-21',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:7000L],
                [key:'c2',date:'2026-11-22',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:3000L]])
    }
    static List<YNABTransaction> transactions() {
        [new YNABTransaction(id:'large',date:'2026-11-21',amount:-70000L,payee_name:'WALMART',payee_id:'payee',memo:'User note',account_id:'account',cleared:'cleared',approved:'false',deleted:'false',import_id:'bank-import'),
         new YNABTransaction(id:'small',date:'2026-11-22',amount:-30000L,payee_name:'WALMART',payee_id:'payee',memo:null,account_id:'account',cleared:'cleared',approved:'false',deleted:'false',import_id:'bank-import')]
    }
    static Map remote(YNABTransaction tx) {
        [id:tx.id,date:tx.date,amount:tx.amount,payee_name:tx.payee_name,payee_id:'payee',memo:tx.memo,category_id:tx.category_id,
            account_id:tx.account_id,cleared:tx.getCleared(),approved:tx.isApproved(),deleted:false,import_id:tx.import_id,subtransactions:[]]
    }
}
