package com.ynab.budgetassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import com.ynab.budgetassistant.config.Configuration
import com.ynab.budgetassistant.config.ConfigMigration
import com.ynab.budgetassistant.model.*
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
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
    int learningReads
    boolean loseResponse
    boolean failReadback
    boolean failedRead
    boolean rejectWrite
    final diagnostics = new ListAppender()
    def setup() {
        diagnostics.start()
        LoggerFactory.getLogger(AmazonEnrichment).addAppender(diagnostics)
        server = HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext('/v1/plans/plan') { ex ->
            String path = ex.requestURI.path - '/v1/plans/plan'
            Map response
            if (path == '/settings') response = [data:[settings:[currency_format:[iso_code:'USD']]]]
            else if (path == '/accounts') response = [data:[accounts:[[id:'account',on_budget:true,closed:false,deleted:false]]]]
            else if (path == '/categories') response = [data:[category_groups:[[name:'Spending',categories:[[id:'e',name:'Electronics'],[id:'pets',name:'Pets']]]]]]
            else if (path == '/transactions') { learningReads++; response = [data:[transactions:remote.values() as List]] }
            else {
                String id = path.tokenize('/').last()
                if (ex.requestMethod == 'GET' && failedRead) { failedRead = false; ex.sendResponseHeaders(503,-1); ex.close(); return }
                if (ex.requestMethod == 'PUT') {
                    Map change = json.readValue(ex.requestBody,Map).transaction
                    writes << change
                    if (rejectWrite) { ex.sendResponseHeaders(503,-1); ex.close(); return }
                    remote[id] += json.readValue(json.writeValueAsString(change),Map)
                    remote[id].subtransactions?.eachWithIndex { row,i -> row.id = "${id}-${i}".toString(); row.deleted = false }
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
    def cleanup() {
        LoggerFactory.getLogger(AmazonEnrichment).detachAppender(diagnostics)
        diagnostics.stop()
        ynab?.close(); server?.stop(0)
    }

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
        diagnostics.list*.formattedMessage.any { it.contains('Amazon write readback failed:') && it.contains('durable intent remains pending') }

        when: 'opting out still permits read-back confirmation of an accepted write'
        config.amazonEnrichment = 'memo_only'
        def restarted = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 1
        restarted.preview*.reason == ['allocation_complete']
        new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save -> !state.plans['plan/tx'].pending && state.plans['plan/tx'].status == 'split' }

        when:
        config.amazonEnrichment = 'item_categories'
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 1

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
        [approved:true,category_id:'human'] | false | 'non_owned_category'
        [subtransactions:[[amount:-65000L,category_id:'e']]] | false | 'ineligible_parent'
        [cleared:'reconciled'] | false | 'ineligible_parent'
        [amount:-65001L] | false | 'no_eligible_match'
        [:] | true | 'no_eligible_match'
    }

    def 'unmatched diagnostics distinguish #reason without disclosing transaction contents'() {
        given:
        def order = orderBased()
        order.financialEvidence += evidenceChange
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + change
        if (outsideWindow) remote.tx.date = java.time.LocalDate.parse(order.orderDate).plusDays(15).toString()

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.empty
        result.preview.any { it.reason == 'no_eligible_match' && reason in it.matching_reasons }
        diagnostics.list*.formattedMessage.any { it.contains('Amazon decision:') && it.contains(reason) }
        !diagnostics.list*.formattedMessage.join('\n').contains(order.orderId)
        !diagnostics.list*.formattedMessage.join('\n').contains('Keep me')

        where:
        change                 | evidenceChange           | outsideWindow | reason
        [amount:-64000L]        | [:]                      | false         | 'no_exact_order_amount'
        [account_id:'other']    | [:]                      | false         | 'no_mapped_order_account'
        [:]                    | [:]                      | true          | 'no_valid_order_date_within_14_days'
        [:]                    | [currency:'EUR']         | false         | 'unsupported_order_currency'
        [:]                    | [payment_identity:null]  | false         | 'no_usable_order_identity'
    }

    def 'approved uncategorized purchase is classified without changing its approval'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + [approved:true]

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes.size() == 1
        writes[0].approved == true
        writes[0].subtransactions*.category_id == ['e','pets']
        remote.tx.approved == true
    }

    def 'refunded purchases keep original splits while credits require separate completed item evidence: #completed'() {
        given:
        def order = orderBased()
        order.financialEvidence.fields = [refund_total:6500L]
        def refund = AmazonFixture.event(2167L,'refund') + [complete:completed]
        order.paymentEvents = [AmazonFixture.event(),refund]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',21670L))
        Map creditBaseline = new LinkedHashMap(remote.credit)

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx'),transaction('credit')],[order],ynab,false)

        then:
        result.updated == (completed ? 2 : 1)
        remote.tx.subtransactions*.amount == [-21670L,-43330L]
        remote.tx.subtransactions*.category_id == ['e','pets']
        remote.tx.amount == -65000L
        remote.credit.category_id == null
        !remote.credit.subtransactions
        if (completed) assert result.preview.any { it.transaction_id == 'credit' && it.reason == 'refund_items_unknown' }
        else assert remote.credit == creditBaseline

        when: 'the specific credit gains completed, reconciled refund-item evidence'
        refund.complete = true
        refund.refund_evidence = [version:1,complete:true,currency:'USD',final_net:true,
            full_total_cents:2167L,adjustments:[],adjustments_distinct:true,
            items:[[itemId:'returned-a',productId:'cable',title:'Fixture refund item',cents:2167L]]]
        int previousWrites = writes.size()
        def creditResult = new AmazonEnrichment(config).process([transaction('credit')],[order],ynab,false)

        then:
        creditResult.updated == 1
        writes.size() == previousWrites + 1
        remote.credit.category_id == 'e'
        remote.credit.amount == 21670L
        !remote.credit.subtransactions
        remote.tx.subtransactions*.amount == [-21670L,-43330L]

        where:
        completed << [false,true]
    }

    def 'order-first #shape allocation works without a ledger and marks only uncorroborated matches'() {
        given:
        def order = orderBased()
        if (shape == 'category') order.financialEvidence.items = [[itemId:'a',productId:'cable',title:'Cable',cents:6000L,quantity:1]]
        order.paymentEvents = corroborated ? [AmazonFixture.event()] : []
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + [approved:true]
        byte[] before = new File(config.amazonJournalFile).bytes

        when:
        def preview = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,true)

        then:
        preview.preview[0].status == shape
        preview.preview[0].changes.memo.startsWith(corroborated ? '🤖 Keep me |' : '🤖❓ Keep me |')
        writes.empty
        new File(config.amazonJournalFile).bytes == before

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes == [preview.preview[0].changes]
        remote.tx.amount == -65000L
        remote.tx.approved == true
        remote.tx.memo.endsWith(AmazonFixture.URL)
        if (shape == 'split') {
            assert remote.tx.subtransactions*.amount == [-21670L,-43330L]
            assert remote.tx.subtransactions*.category_id == ['e','pets']
        } else assert remote.tx.category_id == 'e'

        when:
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.size() == 1

        where:
        shape      | corroborated
        'category' | false
        'split'    | false
        'category' | true
        'split'    | true
    }

    def 'an order-only allocation still owns its purchase after its bank parent leaves the window with cached fallback #cachedFallback'() {
        given:
        def order = orderBased()
        remote.original = AmazonEnrichment.snapshot(AmazonFixture.transaction('original')) + [date:'2026-11-06']
        assert new AmazonEnrichment(config).process([transaction('original')],[order],ynab,false).updated == 1
        order.paymentEvents = [AmazonFixture.event()]
        remote.other = AmazonEnrichment.snapshot(AmazonFixture.transaction('other'))
        if (cachedFallback) new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.plans['plan/other'] = [trusted:true,status:'fallback',order_id:order.orderId,event:AmazonFixture.event(),
                result:new LinkedHashMap(remote.other),user_text:'Keep me']
            save(state)
        }
        Map before = new LinkedHashMap(remote.other)

        when: 'only a different bank parent is in the next fetch'
        def result = new AmazonEnrichment(config).process([transaction('other')],[order],ynab,false)

        then:
        result.updated == 0
        writes.size() == 1
        remote.other == before

        when: 'a genuine refund for that order arrives'
        order.paymentEvents = [AmazonFixture.event(6500L,'refund') + [refund_evidence:order.financialEvidence]]
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',65000L))
        new AmazonEnrichment(config).process([transaction('credit')],[order],ynab,false)

        then:
        writes.size() == 2
        remote.credit.subtransactions*.amount == [21670L,43330L]

        where:
        cachedFallback << [false,true]
    }

    def 'a refund never matches a credit solely near the original purchase date'() {
        given:
        def order = AmazonFixture.order()
        order.orderDate = '2026-09-01'
        order.paymentEvents = [AmazonFixture.event(6500L,'refund') + [refund_evidence:order.financialEvidence]]
        remote.old = AmazonEnrichment.snapshot(AmazonFixture.transaction('old',65000L)) + [date:'2026-09-02']

        when:
        new AmazonEnrichment(config).process([transaction('old')],[order],ynab,false)

        then:
        writes.empty
        remote.old.memo == 'Keep me'
        remote.old.category_id == null

        when: 'the credit is near the actual refund date'
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',65000L)) + [date:'2026-11-21']
        new AmazonEnrichment(config).process([transaction('credit')],[order],ynab,false)

        then:
        writes.size() == 1
        remote.credit.subtransactions*.amount == [21670L,43330L]
        remote.credit.subtransactions*.category_id == ['e','pets']
    }

    def 'a stored refund link cannot restore a purchase-date match contradicted by current payment evidence'() {
        given:
        def order = AmazonFixture.order()
        order.orderDate = '2026-09-01'
        Map refund = AmazonFixture.event(6500L,'refund') + [refund_evidence:order.financialEvidence]
        order.paymentEvents = [refund]
        remote.old = AmazonEnrichment.snapshot(AmazonFixture.transaction('old',65000L)) + [date:'2026-09-02']
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.plans['plan/old'] = [trusted:true,status:'fallback',order_id:order.orderId,
                event:refund + [matching_dates:[order.orderDate]],result:new LinkedHashMap(remote.old),user_text:'Keep me']
            save(state)
        }

        when:
        new AmazonEnrichment(config).process([transaction('old')],[order],ynab,false)

        then:
        writes.empty
        remote.old.category_id == null
        remote.old.memo == 'Keep me'
    }

    def 'newly qualified order details upgrade the existing trusted payment fallback without changing its association'() {
        given:
        def order = AmazonFixture.order()
        order.financialEvidence = [:]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        assert new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false).updated == 1

        when:
        order.financialEvidence = orderBased().financialEvidence
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes.size() == 2
        remote.tx.subtransactions*.amount == [-21670L,-43330L]
        remote.tx.subtransactions*.category_id == ['e','pets']
        remote.tx.memo.count('Keep me') == 1
    }

    def 'unresolved #shape categories remain retryable until all item categories are known'() {
        given:
        def order = orderBased()
        if (shape == 'category') order.financialEvidence.items = [[itemId:'a',productId:'cable',title:'Cable',cents:6000L,quantity:1]]
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings.remove('amazon/plan/cable'); save(state)
        }
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + [approved:true]

        when:
        def unresolved = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        unresolved.preview[0].status == 'fallback'
        unresolved.preview[0].reason == 'unresolved_categories'
        remote.tx.category_id == null
        !remote.tx.subtransactions
        remote.tx.approved == true

        when: 'a later approved mapping supplies the missing category'
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings['amazon/plan/cable'] = ['e']; save(state)
        }
        def resolved = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        resolved.updated == 1
        writes.size() == 2
        if (shape == 'category') assert remote.tx.category_id == 'e'
        else assert remote.tx.subtransactions*.category_id == ['e','pets']
        remote.tx.approved == true

        where:
        shape << ['category','split']
    }

    def 'a valid provider answer rejected for #rejection remains a retryable memo fallback'() {
        given:
        def order = orderBased()
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings.remove('amazon/plan/cable'); save(state)
        }
        def key = ProviderKeys.stage(new File(directory,'config.yml').toPath(),'jev','fixture-key'.toCharArray(),'amazon')
        config.amazonCategorization = RemoteItemClassifier.defaults('jev') + [policy_version:'amazon-choice-v1',
            consent:true,categories:[[id:'e'],[id:'pets']],credentials_file:key.toString()]
        config.amazonCategorization.acceptance.margin = marginGate
        int calls = 0
        def classifier = new RemoteItemClassifier(config.amazonCategorization,{ endpoint,secret,body,limits ->
            calls++
            [status:200,body:json.writeValueAsString([model:'jev-1.14.0',usage:[input_tokens:100,output_tokens:0],
                answers:[item_0:[type:'choice',choice:'Electronics',confidence:confidence,
                    probabilities:['Electronics':probability,'Pets':second,'Insufficient or ambiguous evidence':abstain]]]])]
        })

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,[] as Set,classifier)

        then:
        calls == 1
        result.preview[0].reason == 'unresolved_categories'
        result.preview[0].status == 'fallback'
        remote.tx.category_id == null
        !remote.tx.subtransactions
        diagnostics.list*.formattedMessage.any { it.contains("reasons=[${rejection}]") && it.contains("probability=${probability}") }
        !diagnostics.list*.formattedMessage.join('\n').contains('Electronics')
        !diagnostics.list*.formattedMessage.join('\n').contains(order.orderId)
        diagnostics.list*.formattedMessage.any { it.contains('Amazon write verified:') && it.contains('status=fallback') }

        when: 'an unchanged retry reuses the rejected classification without billing or rewriting'
        def retry = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,[] as Set,classifier)

        then:
        calls == 1
        writes.size() == 1
        retry.preview[0].reason == 'unchanged'
        retry.preview[0].allocation_reason == 'unresolved_categories'

        where:
        probability | second | abstain | confidence | marginGate | rejection
        0.7         | 0.2    | 0.1     | 0.95       | 0.2        | 'probability_below_threshold'
        0.9         | 0.05   | 0.05    | 0.7        | 0.2        | 'confidence_below_threshold'
        0.85        | 0.1    | 0.05    | 0.95       | 0.8        | 'margin_below_threshold'
    }

    def 'manual #edit after a memo fallback is preserved even when automatic categories become available'() {
        given:
        def order = orderBased()
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save -> state.mappings.clear(); save(state) }
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + [approved:approved]
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        remote.tx.category_id = edit == 'category' ? 'human' : 'split'
        if (edit == 'split') remote.tx.subtransactions = [
            [id:'human-1',amount:-10000L,category_id:'human',memo:'My first split'],
            [id:'human-2',amount:-55000L,category_id:'pets',memo:'My other split']]
        Map before = json.readValue(json.writeValueAsString(remote.tx),Map)
        writes.clear()
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings = ['amazon/plan/cable':['e'],'amazon/plan/pet':['pets']]; save(state)
        }

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 0
        writes.empty
        remote.tx == before
        diagnostics.list*.formattedMessage.any { it.contains('Amazon decision:') && it.contains('reason=parent_changed') }

        where:
        edit       | approved
        'category' | true
        'category' | false
        'split'    | true
        'split'    | false
    }

    def 'legacy completed #shape records with blank categories can upgrade but never overwrite later human edits'() {
        given:
        def order = orderBased()
        if (shape == 'category') order.financialEvidence.items = [[itemId:'a',productId:'cable',title:'Cable',cents:6000L,quantity:1]]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        // Model the older journal contract: an accepted financial allocation was
        // labeled complete even when an item category was null.
        if (shape == 'category') remote.tx.category_id = null
        else remote.tx.subtransactions[0].category_id = null
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.plans['plan/tx'].result = json.readValue(json.writeValueAsString(remote.tx),Map)
            state.plans['plan/tx'].lines[0].category_id = null
            state.mappings.remove('amazon/plan/cable')
            save(state)
        }
        if (humanEdit) {
            if (shape == 'category') remote.tx.category_id = 'human'
            else remote.tx.subtransactions[0].category_id = 'human'
        }
        Map before = json.readValue(json.writeValueAsString(remote.tx),Map)
        writes.clear()

        when: 'unresolved classification must not complete or destroy existing splits'
        def unresolved = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        !unresolved.preview.any { it.reason in ['allocation_complete','applied_plan_drift'] }
        remote.tx == before
        if (!humanEdit) assert new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save ->
            state.plans['plan/tx'].status == 'fallback'
        }

        when: 'opting out does not discard ownership of an existing partial allocation'
        writes.clear()
        config.amazonEnrichment = 'memo_only'
        if (shape == 'split') new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        writes.empty
        remote.tx == before

        when:
        config.amazonEnrichment = 'item_categories'
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings['amazon/plan/cable'] = ['e']; save(state)
        }
        def resolved = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        resolved.updated == (humanEdit ? 0 : 1)
        if (humanEdit) {
            assert writes.empty
            assert remote.tx == before
        } else if (shape == 'category') assert remote.tx.category_id == 'e'
        else assert remote.tx.subtransactions*.category_id == ['e','pets']

        where:
        shape      | humanEdit
        'category' | false
        'split'    | false
        'category' | true
        'split'    | true
    }

    def 'payment history can resolve tied orders but unavailable history never chooses a tie'() {
        given:
        def order = orderBased()
        def other = orderBased()
        other.orderId = '114-0000000-1111111'
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())

        when:
        new AmazonEnrichment(config).process([transaction('tx')],[other,order],ynab,false)

        then:
        writes.empty

        when:
        order.paymentEvents = [AmazonFixture.event()]
        new AmazonEnrichment(config).process([transaction('tx')],[other,order],ynab,false)

        then:
        writes.size() == 1
        remote.tx.memo.startsWith('🤖 Keep me |')
        remote.tx.memo.endsWith(AmazonFixture.URL)
        remote.tx.subtransactions*.amount == [-21670L,-43330L]
    }

    def 'order-first matching preserves financial identity ambiguity and human-edit guards: #condition'() {
        given:
        def order = orderBased()
        order.financialEvidence.putAll(evidenceChange)
        order.paymentEvents = payments
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction()) + parentChange
        List parents = [transaction('tx')]
        if (condition == 'excluded competitor') {
            remote.other = AmazonEnrichment.snapshot(AmazonFixture.transaction('other')) + [memo:'Human memo',category_id:'human']
            parents << transaction('other')
        }

        when:
        new AmazonEnrichment(config).process(parents,[order],ynab,false,
            condition == 'excluded competitor' ? ['other'] as Set : [] as Set)

        then:
        writes.empty

        where:
        condition             | evidenceChange                           | parentChange              | payments
        'unknown items'       | [complete:false]                         | [:]                       | []
        'unmapped card'       | [payment_identity:'python:visa:4321']     | [:]                       | []
        'unknown funding'     | [gift_cents:null]                        | [:]                       | []
        'reward funding'      | [reward_cents:100L]                      | [:]                       | []
        'old backfilled order'| [purchase_eligible:false]                | [:]                       | []
        'currency'            | [currency:'CAD']                         | [:]                       | []
        'precision'           | [:]                                      | [amount:-65001L]          | []
        'human category'      | [:]                                      | [category_id:'human']     | []
        'reconciled'          | [:]                                      | [cleared:'reconciled']    | []
        'excluded competitor' | [:]                                      | [:]                       | []
        'partial charge'      | [:]                                      | [:]                       | [AmazonFixture.event(-3000L)]
        'other payment card'  | [:]                                      | [:]                       | [AmazonFixture.event() + [payment_identity:'python:visa:4321']]
        'duplicate payments'  | [:]                                      | [:]                       | [AmazonFixture.event(),AmazonFixture.event(-6500L,'second')]
    }

    def 'repeat purchases match the uniquely closest accepted #basis date independent of input order'() {
        given:
        def first = AmazonFixture.order()
        first.orderDate = '2026-11-08'
        first.paymentEvents = [AmazonFixture.event() + [date:'2026-11-10',matching_dates:['2026-11-09','2026-11-10']]]
        def second = AmazonFixture.order()
        second.orderId = '114-0000000-1111111'
        second.orderDate = '2026-11-17'
        second.paymentEvents = [AmazonFixture.event(-6500L,'second') +
            [order_id:second.orderId,date:'2026-11-19',matching_dates:['2026-11-18','2026-11-19']]]
        remote.first = AmazonEnrichment.snapshot(AmazonFixture.transaction('first')) + [date:firstDate]
        remote.second = AmazonEnrichment.snapshot(AmazonFixture.transaction('second')) + [date:secondDate]
        def parents = [transaction('first'),transaction('second')]
        def orders = [first,second]
        if (orderOnly) orders.each {
            it.financialEvidence += [payment_identity:'python:visa:1234',purchase_eligible:true,payments_complete:false]
            it.paymentEvents = []
        }

        when:
        def result = new AmazonEnrichment(config).process(reverse ? parents.reverse() : parents,
            reverse ? orders.reverse() : orders,ynab,false)

        then:
        result.updated == 2
        writes.size() == 2
        remote.first.memo.endsWith(AmazonFixture.URL)
        remote.second.memo.endsWith('orderID=' + second.orderId)
        [remote.first,remote.second].every { it.subtransactions*.amount == [-21670L,-43330L] }

        where:
        basis        | firstDate    | secondDate   | reverse | orderOnly
        'order'      | '2026-11-08' | '2026-11-17' | false   | false
        'global'     | '2026-11-10' | '2026-11-19' | true    | false
        'ledger'     | '2026-11-09' | '2026-11-18' | false   | false
        'order only' | '2026-11-08' | '2026-11-17' | true    | true
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
        remote.tx.memo.startsWith(eventEvidence ? '🤖 Keep me |' : '🤖❓ Keep me |')
        remote.tx.memo.endsWith(AmazonFixture.URL)
        remote.tx.findAll { k,v -> k != 'memo' } == before.findAll { k,v -> k != 'memo' }

        when: 'financial mode remains ineligible even after a trusted memo write'
        config.amazonEnrichment = 'item_categories'
        order.paymentEvents = [AmazonFixture.event()]
        result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 0
        writes.size() == 1
        result.preview.any { it.reason == (state == 'approved' ? 'non_owned_category' : 'ineligible_parent') }

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
        assert learningReads == 0
        remote.tx.approved = true
        remote.tx.subtransactions[0].category_id = 'pets'

        when:
        def refund = AmazonFixture.event(6500L,'refund')
        refund.refund_evidence = order.financialEvidence
        order.paymentEvents = [refund]
        remote.credit = AmazonEnrichment.snapshot(AmazonFixture.transaction('credit',65000L))
        new AmazonEnrichment(config).process([transaction('credit')],[order],ynab,false)

        then:
        learningReads == 1
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

    def 'event-only historical payments compete without authorizing backfill for sign #sign'() {
        given:
        def order = AmazonFixture.order()
        order.orderDate = '2026-11-21'
        Map currentEvent = AmazonFixture.event(sign * 6500L) + [date:'2026-11-21']
        if (sign > 0) currentEvent.refund_evidence = order.financialEvidence
        order.paymentEvents = [currentEvent]
        String oldId = '114-1111111-2222222'
        Map oldEvent = AmazonFixture.event(sign * 6500L,'old') + [order_id:oldId,date:'2026-11-19',competition_only:true]
        def historical = new PythonAmazonOrderFetcher(config).mapEnvelope(json.writeValueAsString([
            schema_version:1,orders:[[order_number:oldId,payment_events:[oldEvent]]]
        ])).first()
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction('tx',sign * 65000L))

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
        writes*.subtransactions*.amount == [[sign * 21670L,sign * 43330L]]

        where:
        sign << [-1L,1L]
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
        diagnostics.list*.formattedMessage.any { it.contains('reason=legacy_processed_exclusion') }

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

    def 'pending #intent financial intent waits for renewed opt-in without losing recovery'() {
        given:
        def order = AmazonFixture.order()
        if (intent == 'fallback') order.financialEvidence = [:]
        if (intent == 'category') order.financialEvidence.items = [order.financialEvidence.items[0] + [cents:6000L]]
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        rejectWrite = true
        assert new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false).updated == 0
        assert writes.size() == 1
        byte[] pending = new File(config.amazonJournalFile).bytes
        config.amazonEnrichment = 'memo_only'
        rejectWrite = false

        when:
        def dry = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,true)
        def live = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        dry.preview*.reason == ['financial_opt_in_required']
        live.preview*.reason == ['financial_opt_in_required']
        !dry.preview.any { it.changes }
        !live.preview.any { it.changes }
        writes.size() == 1
        remote.tx.memo == 'Keep me'
        new File(config.amazonJournalFile).bytes == pending

        when:
        config.amazonEnrichment = 'item_categories'
        def resumed = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)

        then:
        resumed.updated == 1
        writes.size() == 2
        writes[1] == writes[0]
        new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save -> !state.plans['plan/tx'].pending }

        where:
        intent << ['category','split','fallback']
    }

    def 'migration preserves an Amazon dry-run proposal and journal state'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File legacy = new File(directory, 'legacy.yml')
        legacy.text = new Yaml().dump([ynab:[api_key:'fixture', budget_id:'plan', base_url:config.ynabBaseUrl],
            amazon:[enrichment:config.amazonEnrichment, journal_file:config.amazonJournalFile,
                categorization:config.amazonCategorization, payment_accounts:config.amazonPaymentAccounts], app:[dry_run:true]])
        Files.setPosixFilePermissions(legacy.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'migrated.yml')
        ConfigMigration.run(['--input', legacy.path, '--output', output.path,
            '--legacy-working-directory', directory.path, '--apply'] as String[])
        def migrated = new Configuration()
        migrated.loadConfiguration(output)
        byte[] before = new File(config.amazonJournalFile).bytes

        when:
        def baseline = new AmazonEnrichment(config).process([transaction('tx')], [order], ynab, true)
        def converted = new AmazonEnrichment(migrated).process([transaction('tx')], [order], ynab, true)

        then:
        baseline.preview.any { it.changes }
        converted.preview == baseline.preview
        writes.empty
        new File(config.amazonJournalFile).bytes == before
    }

    def 'a relocated credential path retains the prior pending intent fingerprint'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        config.amazonCategorization.credentials_file = 'credentials/provider.json'
        rejectWrite = true
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false)
        assert writes.size() == 1
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File legacy = new File(directory,'legacy.yml')
        legacy.text = new Yaml().dump([ynab:[api_key:'fixture',budget_id:'plan',base_url:config.ynabBaseUrl],
            amazon:[enrichment:'item_categories',journal_file:config.amazonJournalFile,
                categorization:config.amazonCategorization,payment_accounts:config.amazonPaymentAccounts], app:[dry_run:false]])
        Files.setPosixFilePermissions(legacy.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory,'migrated.yml')
        ConfigMigration.run(['--input',legacy.path,'--output',output.path,'--legacy-working-directory',directory.path,'--apply'] as String[])
        def migrated = new Configuration()
        migrated.loadConfiguration(output)
        rejectWrite = false

        when:
        def result = new AmazonEnrichment(migrated).process([transaction('tx')],[order],ynab,false)

        then:
        result.updated == 1
        writes.size() == 2
        writes[1] == writes[0]
        new ProtectedJournal(config.amazonJournalFile).locked(true) { state,save -> !state.plans['plan/tx'].pending }
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

    def 'confirmed funding failures name #provider in guarded Amazon memos and disappear after mapping recovery'() {
        given:
        def order = AmazonFixture.order()
        order.paymentEvents = [AmazonFixture.event()]
        remote.tx = AmazonEnrichment.snapshot(AmazonFixture.transaction())
        remote.tx.memo = 'Keep my note'
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save -> state.mappings.clear(); save(state) }
        def key = ProviderKeys.stage(new File(directory,'config.yml').toPath(),provider,'fixture-key'.toCharArray(),'amazon')
        config.amazonCategorization = RemoteItemClassifier.defaults(provider) + [policy_version:'amazon-choice-v1',
            consent:true,categories:[[id:'e'],[id:'pets']],credentials_file:key.toString()]
        int calls = 0
        def classifier = new RemoteItemClassifier(config.amazonCategorization,{ a,b,c,d ->
            calls++; [status:provider == 'jev' ? 402 : 429,body:'{"error":{"code":"credit_balance_exhausted"}}']
        })

        when:
        def result = new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,[] as Set,classifier)

        then:
        calls == 1 && writes.size() == 1
        result.preview.last().reason == 'provider_funding_unavailable'
        remote.tx.memo.contains('[ERROR: ' + name + ' classification unavailable; check funding/billing]')
        remote.tx.memo.contains(url)
        remote.tx.memo.contains('Keep my note') && remote.tx.memo.contains(AmazonFixture.URL)
        remote.tx.memo.length() <= 500
        !remote.tx.category_id && !remote.tx.subtransactions

        when:
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,[] as Set,classifier)

        then:
        calls == 1 && writes.size() == 1

        when:
        new ProtectedJournal(config.amazonJournalFile).locked(false) { state,save ->
            state.mappings = ['amazon/plan/cable':['e'],'amazon/plan/pet':['pets']]; save(state)
        }
        new AmazonEnrichment(config).process([transaction('tx')],[order],ynab,false,[] as Set,classifier)

        then:
        calls == 1 && writes.size() == 2
        !remote.tx.memo.contains('[ERROR:') && !remote.tx.memo.contains(url)
        remote.tx.memo.contains('Keep my note')
        remote.tx.subtransactions*.category_id == ['e','pets']

        where:
        provider | name     | url
        'jev'    | 'Jev'    | 'https://console.typesafe.ai/billing'
        'openai' | 'OpenAI' | 'https://platform.openai.com/settings/organization/billing/overview'
    }

    private static AmazonOrder orderBased() {
        def order = AmazonFixture.order()
        order.financialEvidence += [payment_identity:'python:visa:1234',purchase_eligible:true,payments_complete:false]
        order
    }

    private YNABTransaction transaction(String id) {
        Map record = remote[id]
        def tx = new YNABTransaction()
        record.findAll { k,v -> tx.hasProperty(k) }.each { k,v -> tx."${k}" = k in ['approved','deleted'] ? v.toString() : v }
        tx
    }
}
