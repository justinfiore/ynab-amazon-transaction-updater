package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import com.ynab.amazon.YNABAmazonTransactionUpdater
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll
import java.nio.file.Path

/** Actual YNAB HTTP contract tests with account-free provider transports. */
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
    def '#provider decisions allocate exact amounts, persist isolated cache and block frozen policy drift'() {
        given:
        Path key=WalmartProviderKeys.stage(new File(directory,'config.yml').toPath(),provider,'synthetic-not-live'.toCharArray())
        config.walmartCategorization=WalmartRemoteClassifier.defaults(provider)+[credentials_file:key.toString(),categories:[[id:'g'],[id:'h']],
            acceptance:[qualified:true,evidence_id:'mock-only',probability:0.8,confidence:0.8,margin:0.2]]
        config.walmartCategorization.acceptance.qualified_for=new WalmartRemoteClassifier(config.walmartCategorization).qualificationFingerprint(
            WalmartAllocation.categories(ynab.getWalmartCategories(),config.walmartCategorization.categories,'g'))
        int calls=0
        Closure transport={ endpoint,secret,body,limits ->
            calls++
            Map request=json.readValue(body,Map)
            List names=provider=='openai' ? request.questions*.name : request.questions.keySet().toList()
            Map answers=names.collectEntries { name ->
                String label=name=='item_0' ? 'Groceries' : 'Household'
                Map scores=['Groceries':label=='Groceries' ? 0.96 : 0.02,'Household':label=='Household' ? 0.96 : 0.02,(WalmartRemoteClassifier.ABSTAIN):0.02]
                [(name):[name:name,type:'choice',choice:label,confidence:0.95,probabilities:provider=='openai' ? scores.collect { k,v -> [value:k,probability:v] } : scores]]
            }
            [status:200,body:json.writeValueAsString([model:request.model,answers:provider=='openai' ? answers.values().toList() : answers,usage:[input_tokens:100,output_tokens:0]])]
        }
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])

        when:
        new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))

        then:
        remote.large.subtransactions*.amount==[-20000L,-50000L]
        remote.large.subtransactions*.category_id==['h','g']
        remote.small.category_id=='g'
        calls==1
        !new File(config.walmartJournalFile).text.contains('synthetic-not-live')
        new WalmartJournal(config.walmartJournalFile).locked(true) { state,save -> state.inferred.size()==2 && state.inferred.values().every { it.source==provider } }

        when: 'preview uses the same cached decisions and nonsecret provenance for an unchanged frozen plan'
        byte[] beforePreview=new File(config.walmartJournalFile).bytes
        def frozenPreview=new WalmartEnrichment(config).process(matches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        !frozenPreview.preview*.reason.contains('applied_plan_drift')
        frozenPreview.preview[0].operations[0].lines*.category_id==['h','g']
        calls==1 && writes.size()==2
        Arrays.equals(beforePreview,new File(config.walmartJournalFile).bytes)

        when: 'a new order previews the same cached financial plan as live execution, without uploading or saving'
        def nextOrder=WalmartFixture.order(); nextOrder.orderId='456'; nextOrder.orderUrl='https://www.walmart.com/orders/details?orderId=456'
        def nextTransactions=WalmartFixture.transactions()
        nextTransactions.each { it.id='next-'+it.id; remote[it.id]=WalmartFixture.remote(it) }
        def nextMatches=new TransactionMatcher().findWalmartMatches(nextTransactions,[nextOrder])
        def cachedPreview=new WalmartEnrichment(config).process(nextMatches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        cachedPreview.preview[0].operations[0].lines*.category_id==['h','g']
        cachedPreview.preview[0].operations[0].lines*.cents==[2000L,5000L]
        calls==1 && writes.size()==2
        Arrays.equals(beforePreview,new File(config.walmartJournalFile).bytes)

        when: 'a fresh classifier and process reuse protected decisions without remote calls or duplicate PUTs'
        def liveCached=new WalmartEnrichment(config).process(nextMatches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        calls==1
        writes.size()==4
        liveCached.preview[0].operations==cachedPreview.preview[0].operations

        when:
        config.walmartCategorization.categories[1].description='Changed category boundary'
        def drift=new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        writes.size()==4
        drift.preview*.reason.contains('applied_plan_drift')

        when: 'an acceptance or connection change cannot reuse the previous inferred decision'
        def freshOrder=WalmartFixture.order(); freshOrder.orderId='789'; freshOrder.orderUrl='https://www.walmart.com/orders/details?orderId=789'
        def freshTransactions=WalmartFixture.transactions()
        freshTransactions.each { it.id='fresh-'+it.id; remote[it.id]=WalmartFixture.remote(it) }
        def freshMatches=new TransactionMatcher().findWalmartMatches(freshTransactions,[freshOrder])
        config.walmartCategorization.acceptance.margin=0.3
        def acceptanceChanged=new WalmartEnrichment(config).process(freshMatches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        config.walmartCategorization.acceptance.margin=0.2
        Path replacement=WalmartProviderKeys.stage(new File(directory,'replacement-config.yml').toPath(),provider,'synthetic-not-live'.toCharArray())
        config.walmartCategorization.credentials_file=replacement.toString()
        def connectionChanged=new WalmartEnrichment(config).process(freshMatches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))

        then:
        acceptanceChanged.preview*.reason.contains('inference_unavailable')
        connectionChanged.preview*.reason.contains('inference_unavailable')
        calls==1
        !new File(config.walmartJournalFile).text.contains('synthetic-not-live')

        where:
        provider << ['openai','jev']
    }

    @Unroll
    def '#provider repeated products share inference and recover a partial write without false plan drift'() {
        given:
        Path key=WalmartProviderKeys.stage(new File(directory,'config.yml').toPath(),provider,'synthetic-not-live'.toCharArray())
        config.walmartCategorization=WalmartRemoteClassifier.defaults(provider)+[credentials_file:key.toString(),categories:[[id:'g'],[id:'h']],
            acceptance:[qualified:true,evidence_id:'mock-only',probability:0.8,confidence:0.8,margin:0.2]]
        config.walmartCategorization.acceptance.qualified_for=new WalmartRemoteClassifier(config.walmartCategorization).qualificationFingerprint(
            WalmartAllocation.categories(ynab.getWalmartCategories(),config.walmartCategorization.categories,'g'))
        int calls=0
        int questions=0
        Closure transport={ endpoint,secret,body,limits ->
            calls++
            Map request=json.readValue(body,Map)
            List names=provider=='openai' ? request.questions*.name : request.questions.keySet().toList()
            questions+=names.size()
            Map answers=names.collectEntries { name ->
                String label=name=='item_0' ? 'Groceries' : 'Household'
                // Repeated titles would receive individually valid but different provenance if sent twice.
                BigDecimal probability=name=='item_2' ? 0.94G : 0.96G
                Map scores=['Groceries':label=='Groceries' ? probability : 0.98G-probability,
                    'Household':label=='Household' ? probability : 0.98G-probability,(WalmartRemoteClassifier.ABSTAIN):0.02G]
                [(name):[name:name,type:'choice',choice:label,confidence:probability-0.01G,
                    probabilities:provider=='openai' ? scores.collect { k,v -> [value:k,probability:v] } : scores]]
            }
            [status:200,body:json.writeValueAsString([model:request.model,answers:provider=='openai' ? answers.values().toList() : answers,
                usage:[input_tokens:100,output_tokens:0]])]
        }
        def order=WalmartFixture.order()
        order.items[1].grossCents=1000L
        order.items[1].quantity=1G
        order.items << new WalmartOrderItem(itemId:'c',productId:'soap',title:'Soap',quantity:1,grossCents:1000L)
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[order])
        failReadback=true

        when: 'the first write succeeds remotely but its readback is unavailable'
        new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        thrown(IllegalStateException)
        writes*.id==['large']

        when: 'an unchanged preview resolves the same decisions without calls or persistent changes'
        byte[] beforePreview=new File(config.walmartJournalFile).bytes
        def preview=new WalmartEnrichment(config).process(matches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        !preview.preview*.reason.contains('applied_plan_drift')
        preview.preview[0].operations[0].lines*.category_id==['h','h','g']
        Arrays.equals(beforePreview,new File(config.walmartJournalFile).bytes)
        writes*.id==['large']
        calls==1

        when: 'restart recognizes the first intent and completes only the unfinished payment'
        def resumed=new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        !resumed.preview*.reason.contains('applied_plan_drift')
        writes*.id==['large','small']
        remote.large.subtransactions*.amount==[-10000L,-10000L,-50000L]
        remote.small.category_id=='g'
        calls==1 && questions==2
        new WalmartJournal(config.walmartJournalFile).locked(true) { state,save ->
            state.inferred.size()==2 && state.plans['plan/123'].operations.values().every { it.allocation && !it.pending }
        }

        where:
        provider << ['openai','jev']
    }

    def 'unqualified-provider preview is explicitly unavailable, not an executable uncategorized allocation'() {
        given:
        Path key=WalmartProviderKeys.stage(new File(directory,'config.yml').toPath(),'jev','fixture'.toCharArray())
        config.walmartCategorization=WalmartRemoteClassifier.defaults('jev')+[credentials_file:key.toString(),categories:[[id:'g'],[id:'h']]]
        int calls=0
        def classifier=new WalmartRemoteClassifier(config.walmartCategorization,{ a,b,c,d -> calls++; [:] })
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])
        when:
        def preview=new WalmartEnrichment(config).process(matches,ynab,true,classifier)
        then:
        preview.preview[0].reason=='inference_unavailable'
        preview.preview[0].operations.every { !it.lines }
        calls==0 && writes.empty
        !new File(config.walmartJournalFile).exists()
    }

    def 'provider failure keeps matches metadata-only, dry run and link-only never request inference'() {
        given:
        Path key=WalmartProviderKeys.stage(new File(directory,'config.yml').toPath(),'jev','synthetic-not-live'.toCharArray())
        config.walmartCategorization=WalmartRemoteClassifier.defaults('jev')+[credentials_file:key.toString(),categories:[[id:'g'],[id:'h']],
            acceptance:[qualified:true,evidence_id:'mock-only',probability:0.8,confidence:0.8,margin:0.2]]
        config.walmartCategorization.acceptance.qualified_for=new WalmartRemoteClassifier(config.walmartCategorization).qualificationFingerprint(
            WalmartAllocation.categories(ynab.getWalmartCategories(),config.walmartCategorization.categories,'g'))
        int calls=0
        Closure transport={ a,b,c,d -> calls++; [status:401,body:'not logged'] }
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])

        when:
        def preview=new WalmartEnrichment(config).process(matches,ynab,true,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        calls==0 && writes.empty
        preview.preview[0].reason=='inference_required'

        when:
        new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        calls==1
        writes.every { it.change.keySet()==['memo'] as Set }
        remote.values().every { !it.category_id && !it.subtransactions }

        when:
        config.walmartEnrichment='link_only'
        new WalmartEnrichment(config).process(matches,ynab,false,new WalmartRemoteClassifier(config.walmartCategorization,transport))
        then:
        calls==1
    }

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

    def 'a completed category-only write is not repeated because it has no subtransactions'() {
        given:
        def order=WalmartFixture.order()
        order.items=[new WalmartOrderItem(itemId:'a',productId:'food',title:'Apples',quantity:1,grossCents:10000L)]
        order.adjustments=[]
        order.charges=[[key:'c1',date:'2026-11-21',payment:'VISA',currency:'USD',cents:10000L]]
        order.totalAmount=100.00
        def tx=new YNABTransaction(id:'only',date:'2026-11-21',amount:-100000L,payee_name:'WALMART',payee_id:'payee',account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
        remote.only=WalmartFixture.remote(tx)
        new WalmartJournal(config.walmartJournalFile).locked(false) { state, save ->
            state.mappings=['plan/food':['g']]; save(state)
        }
        def matches=new TransactionMatcher().findWalmartMatches([tx],[order])

        when:
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        int firstWrites=writes.size()
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        firstWrites==1
        writes.size()==1
        writes[0].change.category_id=='g'
        !writes[0].change.containsKey('subtransactions')
        remote.only.category_id=='g'
        !remote.only.subtransactions
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

        when:
        def ambiguous=WalmartFixture.order()
        ambiguous.charges=[ambiguous.charges[0], ambiguous.charges[0]+[key:'equal-second']]
        def equal=WalmartFixture.transactions()[0]
        def second=new YNABTransaction(id:'second',date:equal.date,amount:equal.amount,payee_name:'WALMART',account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
        def third=new YNABTransaction(id:'third',date:equal.date,amount:equal.amount,payee_name:'WALMART',account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
        def ambiguousPreview=new TransactionProcessor(config).processWalmartMatches(new TransactionMatcher().findWalmartMatches([equal,second,third],[ambiguous]),ynab,true)

        then:
        writes.empty
        ambiguousPreview.updated==0
        !new File(config.walmartJournalFile).exists()
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

    def 'deleted and transfer parents are not rewritten and link-only keeps category and approval'() {
        given:
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        remote.large.deleted=true
        remote.small.transfer_account_id='other'
        def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])

        when:
        def result=new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        writes.empty
        result.preview*.reason.contains('parent_changed')
        remote.large.deleted
        remote.small.transfer_account_id=='other'

        when:
        remote.large.deleted=false
        remote.small.remove('transfer_account_id')
        remote.large.category_id='h'
        remote.large.approved=false
        remote.large.memo=null
        remote.small.memo=null
        config.walmartEnrichment='link_only'
        def linked=new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        writes.every { it.change.keySet()==['memo'] as Set }
        remote.large.category_id=='h'
        !remote.large.approved
    }

    def 'a rejected category write is not treated as applied and a second process cannot enter the lock'() {
        given:
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        rejectSmall=true
        def matches=new TransactionMatcher().findWalmartMatches(txs,[WalmartFixture.order()])

        when:
        def rejected=new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        writes*.id==['large']
        !remote.small.category_id
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> !state.plans['plan/123'].operations.small?.allocation }

        when:
        def held=new java.util.concurrent.CountDownLatch(1)
        def entered=new java.util.concurrent.CountDownLatch(1)
        def thread=Thread.start {
            new WalmartJournal(config.walmartJournalFile).locked(false) { state, save ->
                entered.countDown()
                held.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
        new WalmartJournal(config.walmartJournalFile).locked(false) { state, save -> }
        held.countDown()
        thread.join(2000)

        then:
        thrown(IllegalStateException)
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
        writes.size()==2
    }

    def 'a changed fragment amount removes stale learning'() {
        given:
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        def matches=new TransactionMatcher().findWalmartMatches(txs,[order])
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        remote.large.approved=true
        remote.small.approved=true
        remote.small.subtransactions[0].category_id='h'
        remote.small.subtransactions[1].category_id='g'

        when:
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)
        remote.small.subtransactions.find { it.memo=='Apples' }.amount=-9990
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save -> !state.mappings['plan/food'] }
    }

    def 'a non-empty run recomputes only the current plan mapping'() {
        given:
        new WalmartJournal(config.walmartJournalFile).locked(false) { state, save ->
            state.mappings['plan/food']=['h']
            state.mappings['other-plan/food']=['h']
            state.learnedProducts=['plan/food','other-plan/food']
            save(state)
        }
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        txs.each { remote[it.id]=WalmartFixture.remote(it) }
        remote.large.approved=true
        remote.small.approved=true
        remote.small.subtransactions=[
            [id:'s1',amount:-20000,memo:'Soap',category_id:'g',deleted:false],
            [id:'s2',amount:-10000,memo:'Apples',category_id:'g',deleted:false]
        ]
        def matches=new TransactionMatcher().findWalmartMatches(txs,[order])

        when:
        new TransactionProcessor(config).processWalmartMatches(matches,ynab,false)

        then:
        new WalmartJournal(config.walmartJournalFile).locked(true) { state, save ->
            !state.mappings.containsKey('plan/food') && state.mappings['other-plan/food']==['h']
        }
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
