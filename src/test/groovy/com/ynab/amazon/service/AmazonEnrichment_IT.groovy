package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import spock.lang.Specification
import spock.lang.TempDir

/** Source → classification → real HTTP PUT → journal restart, without any account access. */
class AmazonEnrichment_IT extends Specification {
    @TempDir File directory
    final json = new ObjectMapper()
    HttpServer server
    YNABService ynab
    Configuration config
    Map remote = [:]
    List<Map> writes = []
    boolean loseResponse
    boolean failReadback
    boolean failedRead
    boolean rejectWrite
    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext('/v1/plans/plan') { ex ->
            String path = ex.requestURI.path - '/v1/plans/plan'
            Map response
            if (path == '/settings') response = [data:[settings:[currency_format:[iso_code:'USD']]]]
            else if (path == '/accounts') response = [data:[accounts:[[id:'account',on_budget:true,closed:false,deleted:false]]]]
            else if (path == '/categories') response = [data:[category_groups:[[name:'Spending',categories:[[id:'e',name:'Electronics'],[id:'pets',name:'Pets']]]]]]
            else {
                String id = path.tokenize('/').last()
                if (ex.requestMethod == 'GET' && failedRead) { failedRead = false; ex.sendResponseHeaders(503,-1); ex.close(); return }
                if (ex.requestMethod == 'PUT') {
                    Map change = json.readValue(ex.requestBody,Map).transaction
                    writes << change
                    if (rejectWrite) { ex.sendResponseHeaders(503,-1); ex.close(); return }
                    remote[id] += change
                    remote[id].subtransactions?.eachWithIndex { row,i -> row.id = "${id}-${i}"; row.deleted = false }
                    if (remote[id].subtransactions) remote[id].category_id = 'split'
                    if (failReadback) { failedRead = true; failReadback = false }
                    if (loseResponse) { loseResponse = false; ex.close(); return }
                }
                response = [data:[transaction:remote[id]]]
            }
            byte[] bytes = json.writeValueAsBytes(response)
            ex.sendResponseHeaders(200,bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        config = new Configuration(ynabApiKey:'fixture',ynabBudgetId:'plan',ynabBaseUrl:"http://127.0.0.1:${server.address.port}/v1",
            amazonEnrichment:'item_categories',amazonJournalFile:new File(directory,'amazon.json').path,
            amazonCategorization:[categories:[[id:'e'],[id:'pets']]],amazonPaymentAccounts:['python:visa:1234':'account'])
        ynab = new YNABService(config)
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings = ['amazon/plan/cable':['e'],'amazon/plan/pet':['pets']]; save(state)
        }
    }
    def cleanup() { ynab?.close(); server?.stop(0) }

    def 'purchase #sign writes exact item splits and restarts without replay after a lost response'() {
        given:
        def order = AmazonFixture.order()
        def event = AmazonFixture.event(sign * 6500L,sign > 0 ? 'refund' : 'charge')
        if (sign > 0) event.refund_evidence = order.financialEvidence
        order.paymentEvents = [event]
        def tx = AmazonFixture.transaction('tx',sign * 65000L)
        remote.tx = AmazonEnrichment.snapshot(tx)
        Map baseline = new LinkedHashMap(remote.tx)
        loseResponse = true
        failReadback = true

        when:
        new AmazonEnrichment(config).process([tx],[order],ynab,false)

        then:
        thrown(IllegalStateException)
        writes.size() == 1
        writes[0].subtransactions*.amount == [sign * 21670L,sign * 43330L]
        writes[0].subtransactions*.category_id == ['e','pets']
        writes[0].approved == false
        remote.tx.findAll { k,v -> !(k in ['memo','category_id','subtransactions']) } == baseline.findAll { k,v -> !(k in ['memo','category_id','subtransactions']) }
        remote.tx.memo.endsWith(AmazonFixture.URL)

        when:
        def restarted = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 1
        restarted.preview*.reason == ['allocation_complete']
        new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save -> !state.plans['plan/tx'].pending && state.plans['plan/tx'].status == 'split' }

        where:
        sign << [-1L,1L]
    }

    def 'null fallback has an exact dry preview, survives response loss, and progresses once evidence arrives'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence = [:]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        byte[] before = new File(config.amazonJournalFile).bytes

        when:
        def preview = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,true)

        then:
        preview.preview[0].changes.containsKey('category_id')
        preview.preview[0].changes.category_id == null
        !preview.preview[0].changes.containsKey('subtransactions')
        writes.empty
        new File(config.amazonJournalFile).bytes == before

        when:
        loseResponse = true
        def fallback = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes == [preview.preview[0].changes]
        fallback.updated == 1
        remote.tx.amount == -65000L
        remote.tx.memo.startsWith('🤖 Keep me |')

        when:
        order.financialEvidence = AmazonFixture.order().financialEvidence
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 2
        writes[1].subtransactions*.amount == [-21670L,-43330L]
        remote.tx.memo.count('🤖') == 1
        remote.tx.memo.count('Keep me') == 1
    }

    def 'confirmed unchanged #mode records are completed but never proposed or written again'() {
        given:
        config.amazonEnrichment = mode
        def order = AmazonFixture.order()
        order.financialEvidence = [:]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        assert new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false).updated == 1
        byte[] before = new File(config.amazonJournalFile).bytes

        when:
        def dry = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,true)

        then:
        dry.preview*.reason == ['unchanged']
        !dry.preview.any { it.containsKey('changes') }
        dry.completed == ['tx']
        writes.size() == 1
        new File(config.amazonJournalFile).bytes == before

        when:
        def live = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        live.preview*.reason == ['unchanged']
        !live.preview.any { it.containsKey('changes') }
        live.completed == ['tx']
        live.updated == 0
        writes.size() == 1

        where:
        mode << ['memo_only','item_categories']
    }

    def 'human-owned state and ambiguous payment identities never authorize fallback'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        def tx = AmazonFixture.transaction()
        tx.properties.putAll([:])
        remote.tx = AmazonEnrichment.snapshot(tx) + change
        if (removeMapping) config.amazonPaymentAccounts = [:]

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.empty
        result.preview.any { it.reason == reason }

        where:
        change | removeMapping | reason
        [category_id:'human'] | false | 'non_owned_category'
        [approved:true] | false | 'ineligible_parent'
        [subtransactions:[[amount:-65000L,category_id:'e']]] | false | 'ineligible_parent'
        [cleared:'reconciled'] | false | 'ineligible_parent'
        [amount:-65001L] | false | 'payment_evidence_required'
        [:] | true | 'missing_payment_account_mapping'
    }

    def 'multiple charges get independent fallbacks but competing events and parents are never chosen greedily'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event(-3000L,'one'),AmazonFixture.event(-3500L,'two')]
        remote.one = AmazonEnrichment.snapshot(AmazonFixture.transaction('one',-30000L))
        remote.two = AmazonEnrichment.snapshot(AmazonFixture.transaction('two',-35000L))

        when:
        new AmazonEnrichment(config).process([transaction('one'),transaction('two')],[order],ynab,false)

        then:
        writes.size() == 2
        writes.every { it.category_id == null && it.containsKey('category_id') && !it.subtransactions }

        when:
        writes.clear()
        def other = AmazonFixture.order()
        other.orderId = '114-0000000-1111111'
        other.paymentEvents = [AmazonFixture.event(-3000L,'three') + [order_id:other.orderId]]
        new AmazonEnrichment(config).process([transaction('one')],[order,other],ynab,false)

        then:
        writes.empty
    }

    def 'memo-only preserves reviewed financial state on #state parents with event evidence #eventEvidence'() {
        given:
        config.amazonEnrichment = 'memo_only'
        def order = AmazonFixture.order()
        order.items = [new AmazonOrderItem(title:'Cable',price:65G,quantity:1)]
        order.paymentEvents = eventEvidence ? [AmazonFixture.event()] : []
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + [category_id:'human']
        if (state == 'approved') remote.tx.approved = true
        if (state == 'reconciled') remote.tx.cleared = 'reconciled'
        if (state == 'split') {
            remote.tx.category_id = 'split'
            remote.tx.subtransactions = [[id:'tx-0',amount:-65000L,category_id:'e',memo:'Human split',deleted:false]]
        }
        Map before = json.readValue(json.writeValueAsString(remote.tx),Map)

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes.size() == 1
        writes[0].keySet() == ['memo'] as Set
        remote.tx.memo.startsWith('🤖 Keep me |')
        remote.tx.memo.endsWith(AmazonFixture.URL)
        remote.tx.findAll { k,v -> k != 'memo' } == before.findAll { k,v -> k != 'memo' }

        when: 'financial mode remains ineligible even after a trusted memo write'
        config.amazonEnrichment = 'item_categories'
        order.paymentEvents = [AmazonFixture.event()]
        result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 0
        writes.size() == 1
        result.preview.any { it.reason == 'ineligible_parent' }

        where:
        state        | eventEvidence
        'approved'   | false
        'approved'   | true
        'split'      | false
        'split'      | true
        'reconciled' | false
        'reconciled' | true
    }

    def 'memo-only keeps categories, then trusted null-category metadata can progress without legacy heuristics'() {
        given:
        config.amazonEnrichment = 'memo_only'
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())

        when:
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        !writes[0].containsKey('category_id')
        remote.tx.memo.length() > 100

        when:
        config.amazonEnrichment = 'item_categories'
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 2
        writes[1].subtransactions.size() == 2
        remote.tx.memo.count('Keep me') == 1
    }

    def 'approved provenance learns corrections only from emitted item rows, and original refund categories win'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        remote.tx.approved = true
        remote.tx.subtransactions[0].category_id = 'pets'

        when:
        def refund = AmazonFixture.event(6500L,'refund')
        refund.refund_evidence = order.financialEvidence
        order.paymentEvents = [refund]
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',65000L))
        new AmazonEnrichment(config).process([transaction('credit')],[order],ynab,false)

        then:
        writes.last().subtransactions*.category_id == ['pets','pets']
        new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save -> state.mappings['amazon/plan/cable'] == ['pets'] }
    }

    def 'legacy processed IDs, fully gift funded orders and out-of-window or duplicate events do not write'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())

        expect:
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,['tx'] as Set).updated == 0
        writes.empty

        when:
        order.paymentEvents = []
        order.financialEvidence.gift_cents = 6500L
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.empty
    }

    def 'event-only historical charges compete without authorizing purchase backfill'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        String oldId = '114-1111111-2222222'
        Map oldEvent = AmazonFixture.event(-6500L,'old') + [order_id:oldId,competition_only:true]
        def historical = new PythonAmazonOrderFetcher(config).mapEnvelope(json.writeValueAsString([
            schema_version:1,orders:[[order_number:oldId,payment_events:[oldEvent]]]
        ])).first()
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order,historical],ynab,false)

        then:
        writes.empty
        !result.preview.any { it.changes }
        result.preview.any { it.reason == 'ambiguous_payment_match' }

        when: 'the old charge is the only candidate'
        result = new AmazonEnrichment(config).process([transaction('tx')],[historical],ynab,false)

        then:
        writes.empty
        !result.preview.any { it.changes }

        when: 'only the current qualified charge remains'
        result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes*.subtransactions*.amount == [[-21670L,-43330L]]
    }

    def 'legacy parents remain payment competitors without becoming write targets'() {
        given:
        def order = AmazonFixture.order()
        if (!completeCosts) order.financialEvidence = [:]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        remote.legacy = AmazonEnrichment.snapshot(AmazonFixture.transaction('legacy')) + [approved:approved]
        Map legacyBefore = new LinkedHashMap(remote.legacy)

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx'),transaction('legacy')],[order],ynab,false,['legacy'] as Set)

        then:
        writes.empty
        result.preview.any { it.transaction_id == 'tx' && it.reason == 'ambiguous_or_incomplete_payment' }
        !result.preview.any { it.transaction_id == 'legacy' }
        remote.legacy == legacyBefore

        when: 'the excluded parent no longer competes for this event'
        remote.legacy.amount = -10000L
        result = new AmazonEnrichment(config).process([transaction('tx'),transaction('legacy')],[order],ynab,false,['legacy'] as Set)

        then:
        result.updated == 1
        writes.size() == 1
        remote.legacy == legacyBefore + [amount:-10000L]
        if (completeCosts) assert writes[0].subtransactions*.amount == [-21670L,-43330L]
        else assert writes[0].containsKey('category_id') && writes[0].category_id == null

        where:
        approved | completeCosts
        false    | true
        true     | true
        false    | false
        true     | false
    }

    def 'trusted fallback cannot bypass newly incomplete duplicate or competing evidence'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence = [:]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        writes.clear()
        order.financialEvidence = AmazonFixture.order().financialEvidence
        if (variant == 'incomplete') order.paymentEvents[0].complete = false
        if (variant == 'competition_only') order.paymentEvents[0].competition_only = true
        if (variant == 'duplicate') order.paymentEvents << new LinkedHashMap(order.paymentEvents[0])
        if (variant in ['competing_parent','legacy_parent']) remote.other = AmazonEnrichment.snapshot(AmazonFixture.transaction('other'))

        when:
        new AmazonEnrichment(config).process(remote.keySet().collect { transaction(it) },[order],ynab,false,
            variant == 'legacy_parent' ? ['other'] as Set : [] as Set)

        then:
        writes.empty

        where:
        variant << ['incomplete','duplicate','competing_parent','legacy_parent','competition_only']
    }

    def 'an unresolved intent cannot replay against changed policy even when the bank baseline is unchanged'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        rejectWrite = true
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        assert writes.size() == 1
        config.amazonCategorization.categories[0].description = 'changed policy'
        rejectWrite = false

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.preview*.reason == ['pending_intent_drift']
        writes.size() == 1
        remote.tx.memo == 'Keep me'
    }

    def 'explicit refund email components flow through account matching into positive splits and later credits remain distinct'() {
        given:
        String body = '''Return summary
Total refund $59.00
Refunded tax $5.00
[USB-C cable](https://www.amazon.com/gp/product/B000000001)
Quantity: 1
Refund merchandise: $20.00
Promo discount -$6.00
[Dog food](https://www.amazon.com/gp/product/B000000002)
Quantity: 1
Refund merchandise: $40.00
Order #113-8849250-2919462
'''
        def message = Stub(jakarta.mail.Message) {
            getSubject() >> 'Your refund for USB-C cable'
            getSentDate() >> new java.text.SimpleDateFormat('yyyy-MM-dd').parse('2026-11-20')
            getContent() >> body
        }
        def notice = new EmailAmazonOrderFetcher(config).parseRefundFromEmail(message)
        assert notice.refundEvidence.complete
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event(5900L,'credit-1')]
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',59000L))
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings = ['amazon/plan/B000000001':['e'],'amazon/plan/B000000002':['pets']]; save(state)
        }

        when:
        new AmazonEnrichment(config).process([transaction('credit')],[order,notice],ynab,false)

        then:
        writes[0].subtransactions*.amount == [15300L,43700L]
        writes[0].subtransactions*.category_id == ['e','pets']
        writes[0].memo.contains('Refund $59.00 USD')
        writes[0].memo.endsWith(AmazonFixture.URL)

        when:
        order.paymentEvents = [AmazonFixture.event(1000L,'credit-2')]
        remote.later = AmazonEnrichment.snapshot(AmazonFixture.transaction('later',10000L))
        new AmazonEnrichment(config).process([transaction('later')],[order],ynab,false)

        then:
        writes.size() == 2
        writes[1].category_id == null
        !writes[1].subtransactions
        remote.credit.subtransactions*.amount == [15300L,43700L]
    }

    def 'duplicate source sign conventions agree but contradictory identified prices block all writes'() {
        given:
        def order = AmazonFixture.order()
        order.items = [new AmazonOrderItem(asin:'cable',title:'USB-C cable',price:20G,quantity:1)]
        order.paymentEvents = [AmazonFixture.event()]
        def duplicate = new AmazonOrder(orderId:order.orderId,orderDate:order.orderDate,totalAmount:65G,
            items:[new AmazonOrderItem(asin:'cable',title:'USB-C cable',price:price,quantity:1)])
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())

        when:
        new AmazonEnrichment(config).process([transaction('tx')],[order,duplicate],ynab,false)

        then:
        writes.size() == expectedWrites
        if (expectedWrites) assert writes[0].subtransactions*.amount == [-21670L,-43330L]

        where:
        price | expectedWrites
        20G | 1
        21G | 0
    }

    def 'provider failure permits only guarded fallback and dry-run or missing Amazon consent never sends titles'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save -> state.mappings.clear(); save(state) }
        def key = ProviderKeys.stage(new File(directory,'config.yml').toPath(),'jev','fixture-key'.toCharArray(),'amazon')
        config.amazonCategorization = RemoteItemClassifier.defaults('jev') + [policy_version:'amazon-choice-v1',
            consent:consent,categories:[[id:'e'],[id:'pets']],credentials_file:key.toString()]
        int calls = 0
        def classifier = new RemoteItemClassifier(config.amazonCategorization,{ endpoint,secret,body,limits ->
            calls++; throw new IllegalStateException('fixture failure')
        })
        byte[] before = new File(config.amazonJournalFile).bytes

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,dryRun,[] as Set,classifier)

        then:
        calls == expectedCalls
        result.preview[0].status == 'fallback'
        !result.preview[0].changes.subtransactions
        result.preview[0].changes.containsKey('category_id')
        if (dryRun) {
            assert writes.empty
            assert new File(config.amazonJournalFile).bytes == before
            assert result.preview[0].reason == 'inference_required'
        } else assert writes.size() == 1 && writes[0].category_id == null

        where:
        dryRun | consent | expectedCalls
        true | true | 0
        false | false | 0
        false | true | 1
    }

    private YNABTransaction transaction(String id) {
        Map record = remote[id]
        def tx = new YNABTransaction()
        record.findAll { k,v -> tx.hasProperty(k) }.each { k,v -> tx."${k}" = k in ['approved','deleted'] ? v.toString() : v }
        tx
    }
}
