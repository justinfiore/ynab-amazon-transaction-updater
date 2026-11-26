package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import spock.lang.Specification
import spock.lang.TempDir

class AmazonCategorization_UT extends Specification {
    @TempDir File directory

    def 'supplemental evidence requires explicit opt-in outside Python and its own runtime settings'() {
        given:
        def config = new Configuration(ynabApiKey:'fixture',ynabBudgetId:'plan',amazonCsvFilePath:'fixture.csv')

        expect:
        config.amazonEnrichment == 'memo_only'
        !config.isAmazonTransactionEvidenceEnabled()
        !config.amazonRefundEmailEvidenceEnabled
        config.isValid()

        when:
        config.amazonTransactionEvidenceEnabled = true

        then:
        !config.isValid()
        config.lastValidationError.contains('Python runtime/session')

        when:
        config.amazonOrderFetcher = 'python'
        config.amazonTransactionEvidenceEnabled = null
        config.amazonPythonExecutable = 'python'
        config.amazonPythonBridgeScript = 'bridge.py'
        config.amazonPythonConfigPath = 'session.yml'

        then:
        config.isAmazonTransactionEvidenceEnabled()
        config.isValid() // No IMAP credentials required.

        when:
        config.amazonRefundEmailEvidenceEnabled = true

        then:
        !config.isValid()
        config.lastValidationError.contains('IMAP')
    }

    def 'exact event association observes both sides of date precision currency and identity boundaries'() {
        given:
        def tx = AmazonFixture.transaction()
        tx.date = date
        tx.amount = amount
        def event = AmazonFixture.event() + eventChange

        expect:
        AmazonEnrichment.matches(tx,event,['account'] as Set,['python:visa:1234':'account']) == expected

        where:
        date | amount | eventChange | expected
        '2026-12-04' | -65000L | [:] | true
        '2026-12-05' | -65000L | [:] | false
        '2026-11-06' | -65000L | [:] | true
        '2026-11-05' | -65000L | [:] | false
        '2026-11-05' | -65000L | [matching_dates:['2026-11-19']] | true
        '2026-11-04' | -65000L | [matching_dates:['2026-11-19']] | false
        '2026-12-05' | -65000L | [matching_dates:['2026-11-21']] | true
        '2026-12-06' | -65000L | [matching_dates:['2026-11-21']] | false
        '2026-11-20' | -65001L | [:] | false
        '2026-11-20' | -65010L | [:] | false
        '2026-11-20' | -65000L | [payment_identity:'python:visa:4321'] | false
        '2026-11-20' | -65000L | [currency:'CAD'] | false
        '2026-11-20' | -65000L | [order_id:'SUB-123'] | false
    }

    def 'known adjustments and gift funding allocate exact bank cents without default rows'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence.adjustments = adjustments
        order.financialEvidence.full_total_cents = total
        order.financialEvidence.gift_cents = gift
        def event = AmazonFixture.event(-(total-gift))

        when:
        Map result = AmazonAllocation.plan(order,event,[event],['a':[category_id:'e'],'b':[category_id:'e']])
        Map payload = AmazonAllocation.payload(event,'memo',result)

        then:
        result.eligible
        result.lines*.cents == expected
        result.lines*.category_id == ['e','e']
        payload.subtransactions*.amount == expected.collect { -it * 10L }
        payload.subtransactions.sum { it.amount } == -(total-gift)*10L
        payload.approved == false

        where:
        adjustments                                                           | total | gift | expected
        [[id:'tax',kind:'tax',cents:500L]]                                      | 6500L | 0L   | [2167L,4333L]
        [[id:'discount',kind:'discount',cents:-600L],[id:'tax',kind:'tax',cents:500L]] | 5900L | 0L | [1967L,3933L]
        [[id:'discount',kind:'discount',cents:-600L,item_id:'a'],[id:'tax',kind:'tax',cents:500L]] | 5900L | 0L | [1530L,4370L]
        [[id:'shipping',kind:'shipping',cents:300L]]                            | 6300L | 0L   | [2100L,4200L]
        [[id:'tax',kind:'tax',cents:500L]]                                      | 6500L | 1500L | [1667L,3333L]
        [[id:'discount',kind:'discount',cents:-600L,item_id:'a'],[id:'tax',kind:'tax',cents:500L]] | 5900L | 1000L | [1271L,3629L]
    }

    def 'refunds reconcile only explicitly identified credit items and use positive signs'() {
        given:
        def order = AmazonFixture.order()
        def event = AmazonFixture.event(total)
        event.refund_evidence = order.financialEvidence + [full_total_cents:total,adjustments:adjustments]

        when:
        def result = AmazonAllocation.plan(order,event,[event],[:])
        def payload = AmazonAllocation.payload(event,'refund',result)

        then:
        result.eligible
        payload.subtransactions*.amount == expected
        payload.subtransactions*.category_id == [null,null]
        payload.subtransactions*.memo == ['🤖 Refund: USB-C cable','🤖 Refund: Dog food']

        where:
        adjustments | total | expected
        [[id:'tax',kind:'tax',cents:500L]] | 6500L | [21670L,43330L]
        [[id:'d',kind:'discount',cents:-600L,item_id:'a'],[id:'tax',kind:'tax',cents:500L]] | 5900L | [15300L,43700L]
    }

    def 'every financial gate fails closed rather than allowing a balancing line'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence.putAll(change)
        def event = AmazonFixture.event(-6500L)

        expect:
        AmazonAllocation.plan(order,event,[event],[:]).reason == reason

        where:
        change | reason
        [complete:false] | 'incomplete_source'
        [currency:null] | 'unsupported_currency'
        [items:[[itemId:'a',title:'Unknown',cents:null,quantity:1]]] | 'unknown_item_basis'
        [adjustments:[]] | 'unreconciled_costs'
        [adjustments_distinct:false] | 'overlapping_or_unknown_adjustments'
        [gift_cents:null] | 'unsupported_payment_coverage'
        [reward_cents:100L] | 'unsupported_payment_coverage'
        [payments_complete:false] | 'unsupported_payment_coverage'
        [adjustments:[[id:'d',kind:'discount',cents:-7000L]],full_total_cents:0L] | 'negative_item_cost'
    }

    def 'stable ties, zero bank rows and final refund amounts are not guessed'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence.gift_cents = 6499L
        def event = AmazonFixture.event(-1L)

        expect:
        AmazonAllocation.distribute(1L,[z:1L,a:1L]) == [z:0L,a:1L]
        AmazonAllocation.plan(order,event,[event],[:]).lines*.item_id == ['b']
        AmazonAllocation.payload(event,'memo',AmazonAllocation.plan(order,event,[event],[:])) == [memo:'memo',approved:false,category_id:null]
        AmazonAllocation.plan(order,AmazonFixture.event(6500L),[],[:]).reason == 'refund_items_unknown'

        when:
        def credit = AmazonFixture.event(1000L)
        credit.refund_evidence = [version:1,complete:true,currency:'USD',adjustments_distinct:true,final_net:true,
            items:[[itemId:'a',productId:'cable',title:'Cable',cents:0L],[itemId:'b',productId:'pet',title:'Food',cents:1000L]],full_total_cents:1000L]
        def plan = AmazonAllocation.plan(order,credit,[credit],[:])

        then:
        plan.eligible
        plan.lines*.item_id == ['b']
        AmazonAllocation.payload(credit,'memo',plan) == [memo:'memo',approved:false,category_id:null]
    }

    def 'parent metadata and complete mobile link survive Unicode, long titles and user text'() {
        given:
        def order = AmazonFixture.order()
        String expected = '🤖 2026-11-20 | Total $65.00 USD | 2 items: USB-C cable, Dog food | https://www.amazon.com/your-orders/order-details?orderID=113-8849250-2919462'

        expect:
        AmazonMemos.parent(order,AmazonFixture.event(-6500L)) == expected

        when:
        order.financialEvidence.items[0].title = '充電🔌' * 400
        String memo = AmazonMemos.parent(order,AmazonFixture.event(-6500L),'Keep my text')

        then:
        memo.startsWith('🤖 Keep my text | 2026-11-20 | Total $65.00 USD')
        memo.endsWith(AmazonFixture.URL)
        memo.length() <= 500
        AmazonMemos.parent(order,AmazonFixture.event() + [order_only:true],'Keep my text').startsWith('🤖❓ Keep my text |')
        AmazonMemos.parent(order,AmazonFixture.event(-6500L),'x' * 450) == null
        !AmazonMemos.parent(new AmazonOrder(orderId:'SUB-123',items:[new AmazonOrderItem(title:'Delivery')]),null).contains('https:')
        AmazonMemos.parent(new AmazonOrder(orderId:AmazonFixture.ID),AmazonFixture.event(2167L)).contains('Refund $21.67 USD')
        AmazonMemos.parent(new AmazonOrder(orderId:AmazonFixture.ID),AmazonFixture.event()).contains("❌ Amazon Order (Couldn't identify items)")
    }

    def 'approved evidence and inferred cache are isolated by retailer and plan without a default'() {
        given:
        Map state = [mappings:['plan/p':['e'],'amazon/plan/p':['pets','e']],inferred:[:]]
        Map item = [itemId:'a',productId:'p',title:'Cable']
        Map categories = [e:[id:'e',name:'Electronics']]

        expect:
        ItemClassificationService.resolve([item],categories,state,'walmart','plan',null,true,{ }).decisions.a.category_id == 'e'
        ItemClassificationService.resolve([item],categories,state,'amazon','plan',null,true,{ }).decisions.a.conflict
        ItemClassificationService.resolve([item],categories,state,'amazon','other',null,true,{ }).decisions.a.source == 'unresolved'
        ItemClassificationService.cacheKey('amazon','plan',item,'scope') != ItemClassificationService.cacheKey('walmart','plan',item,'scope')
        ItemClassificationService.categories([[id:'e',name:'Electronics']],[[id:'e']]).keySet() == ['e'] as Set
    }
}

class AmazonFixture {
    static final String ID = '113-8849250-2919462'
    static final String URL = 'https://www.amazon.com/your-orders/order-details?orderID=' + ID
    static AmazonOrder order() {
        new AmazonOrder(orderId:ID,orderDate:'2026-11-20',totalAmount:-65G,
            financialEvidence:[version:1,source:'python',complete:true,currency:'USD',payments_complete:true,
                full_total_cents:6500L,gift_cents:0L,reward_cents:0L,adjustments_distinct:true,
                items:[[itemId:'a',productId:'cable',title:'USB-C cable',cents:2000L,quantity:1],
                       [itemId:'b',productId:'pet',title:'Dog food',cents:4000L,quantity:1]],
                adjustments:[[id:'tax',kind:'tax',cents:500L]]])
    }
    static Map event(long amount = -6500L, String key = 'charge') {
        [key:key,order_id:ID,date:'2026-11-20',signed_cents:amount,payment_identity:'python:visa:1234',currency:'USD',complete:true,source:'python']
    }
    static YNABTransaction transaction(String id = 'tx', long amount = -65000L) {
        new YNABTransaction(id:id,date:'2026-11-20',amount:amount,account_id:'account',payee_id:'payee',payee_name:'AMAZON.COM',
            memo:'Keep me',cleared:'cleared',approved:'false',deleted:'false',import_id:'import',flag_color:'blue')
    }
}
