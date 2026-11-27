package com.ynab.amazon.service

import com.ynab.amazon.model.YNABTransaction
import spock.lang.Specification

class SheetzEnrichment_UT extends Specification {
    static YNABTransaction tx(String id = 't', String date = '2026-11-20', long amount = -12340) {
        new YNABTransaction(id:id, date:date, amount:amount, payee_name:'SHEETZ', account_id:'account',
            approved:'false', deleted:'false', cleared:'uncleared')
    }
    static Map record(String type = 'ANYWHERE', String date = '2026-11-23T12:00:00-05:00') {
        [orderType:type, orderNumber:123, orderTime:date, pickupTime:date, orderTotal:12.34, items:[[description:'Sandwich']]]
    }
    static Map snapshot(YNABTransaction tx) {
        [id:tx.id, date:tx.date, amount:tx.amount, payee_name:tx.payee_name, payee_id:null, account_id:tx.account_id,
         approved:false, deleted:false, cleared:'uncleared', memo:tx.memo, category_id:tx.category_id, import_id:null,
         flag_color:null, transfer_account_id:null, subtransactions:[]]
    }

    def 'source permits only fixed safe timeout diagnostics'() {
        given:
        def diagnostic = 'signin_result_timeout trusted_origin=true password_visible=true alert_visible=false challenge_text=false http4xx=true http5xx=false request_failed=false xhr400=false xhr401=true xhr403=false xhr404=false xhr429=false events=401:POST:fetch:orders:auth/redacted'
        expect:
        SheetzSource.safeError(diagnostic, 'FAILED') == diagnostic
        SheetzSource.safeError(diagnostic + ' secret', 'FAILED') == 'FAILED'
        SheetzSource.safeError(diagnostic.replace('redacted', 'private-password'), 'FAILED') == 'FAILED'
        SheetzSource.safeError('signin_result_timeout', 'FAILED') == 'signin_result_timeout'
    }

    def 'exact cents and inclusive date boundary with shared ambiguity'() {
        given:
        def online = SheetzEnrichment.normalizeResponse([orders:[record()]]).orders
        def fuel = SheetzEnrichment.normalizeResponse([orders:[record('RETAIL_FUEL')]]).orders
        expect:
        SheetzEnrichment.plan([tx()], online, ['account'] as Set).t.reason == 'matched'
        SheetzEnrichment.plan([tx('t','2026-11-19')], online, ['account'] as Set).t.reason == 'unmatched'
        SheetzEnrichment.plan([tx('t','2026-11-20',-12350)], online, ['account'] as Set).t.reason == 'unmatched'
        SheetzEnrichment.plan([tx()], online + fuel, ['account'] as Set).t.reason == 'ambiguous'
        SheetzEnrichment.plan([tx(), tx('t2')], online, ['account'] as Set).values()*.reason == ['ambiguous','ambiguous']
    }

    def 'malformed records retain good records and diagnostics without exposing values'() {
        when:
        def result = SheetzEnrichment.normalizeResponse([orders:[record(), record('RETAIL_FUEL') + [orderTotal:'private-invalid-value'],
            record() + [orderNumber:456, items:[[description:'Good item'], null]]]])
        then:
        result.orders*.id == ['123', '456']
        result.orders[1].titles == ['Good item']
        result.incomplete
        result.errors*.field == ['orderTotal', 'items']
        result.errors[0].record == 2
        result.errors[1].item == 2
        !result.errors.toString().contains('private-invalid-value')
    }

    def 'invalid item does not invalidate usable receipt matching'() {
        when:
        def result = SheetzEnrichment.normalizeResponse([orders:[record() + [items:[[description:'Good'], null]]]])
        then:
        !result.incomplete
        result.orders[0].titles == ['Good']
        result.errors[0].affects_matching == false
    }

    def 'valid receipt survives an earlier malformed duplicate'() {
        when:
        def result = SheetzEnrichment.normalizeResponse([orders:[record() + [orderTotal:'invalid'], record()]])
        then:
        result.incomplete
        result.orders*.id == ['123']
        result.errors*.field == ['orderTotal']
    }

    def 'memo keeps user text and replaces only generated notice'() {
        given:
        def order = SheetzEnrichment.normalizeResponse([orders:[record()]]).orders[0]
        def notice = SheetzEnrichment.memo('My 🤖 note', null)
        when: def memo = SheetzEnrichment.memo(notice, order)
        then:
        memo.startsWith('My 🤖 note | 🤖 2026-11-23')
        memo.contains('Order 123 | https://orders.sheetz.com/order/history')
        !memo.contains('No matching')
        SheetzEnrichment.memo(memo, order) == memo
        SheetzEnrichment.memo(memo, null) == notice
        SheetzEnrichment.memo('x' * 500, order) == null
    }

    def 'older transaction competes for a receipt without becoming a write target'() {
        given:
        def target = tx('target', '2026-11-20')
        def earlier = tx('earlier', '2026-11-14')
        def orders = SheetzEnrichment.normalizeResponse([orders:[record('RETAIL_FUEL', '2026-11-17T12:00:00-05:00')]]).orders
        def ynab = Mock(YNABService)
        when:
        def result = SheetzEnrichment.process([target, earlier], orders, ynab, ['account'] as Set,
            false, true, false, null, null, true, ['target'] as Set)
        then:
        result.preview == [[transaction_id:'target', reason:'ambiguous']]
        0 * ynab.putTransaction(_, _)
    }

    def 'unmatched fallback clears prefilled category only after unchanged fresh read'() {
        given:
        def transaction = tx(); transaction.category_id = 'prefilled'
        def current = snapshot(transaction)
        def ynab = Mock(YNABService)
        when:
        def result = SheetzEnrichment.process([transaction], [], ynab, ['account'] as Set, false, true, true, null, null)
        then:
        1 * ynab.getCategoryCatalog() >> []
        1 * ynab.getTransaction('t') >> current
        1 * ynab.putTransaction('t', { it.containsKey('category_id') && it.category_id == null && it.memo.contains('No matching') })
        1 * ynab.getTransaction('t') >> (current + [category_id:null, memo:SheetzEnrichment.memo(null,null)])
        result.updated == 1
    }

    def 'fuel uses eligible configured category and never writes a split'() {
        given:
        def transaction = tx()
        def current = snapshot(transaction)
        def order = SheetzEnrichment.normalizeResponse([orders:[record('RETAIL_FUEL')]]).orders
        def ynab = Mock(YNABService)
        when:
        def result = SheetzEnrichment.process([transaction], order, ynab, ['account'] as Set, false, false, true, 'gas', null)
        then:
        1 * ynab.getCategoryCatalog() >> [[id:'gas']]
        1 * ynab.getTransaction('t') >> current
        1 * ynab.putTransaction('t', [category_id:'gas'])
        1 * ynab.getTransaction('t') >> (current + [category_id:'gas'])
        result.updated == 1
    }

    def 'approved, split, changed and ambiguous transactions receive no writes'() {
        given:
        def approved = tx('a'); approved.approved = 'true'
        def split = tx('s'); split.subtransactions = [[amount:-12340]]
        def changed = tx('c')
        def ynab = Mock(YNABService)
        when:
        SheetzEnrichment.process([approved, split, changed], [], ynab, ['account'] as Set, false, true, false, null, null)
        then:
        1 * ynab.getTransaction('c') >> (snapshot(changed) + [approved:true])
        0 * ynab.putTransaction(_, _)
    }

    def 'missing or ineligible category never produces a fuel category write'() {
        given:
        def order = SheetzEnrichment.normalizeResponse([orders:[record('RETAIL_FUEL')]]).orders
        def ynab = Mock(YNABService)
        when:
        def result = SheetzEnrichment.process([tx()], order, ynab, ['account'] as Set, false, false, true, category, null)
        then:
        1 * ynab.getCategoryCatalog() >> [[id:'internal', internal:true]]
        0 * ynab.getTransaction(_)
        0 * ynab.putTransaction(_, _)
        result.preview*.reason.contains('invalid_gasoline_category')
        where:
        category << [null, 'missing', 'internal']
    }

    def 'ambiguous matches and dry-run never write'() {
        given:
        def orders = SheetzEnrichment.normalizeResponse([orders:[record(), record('RETAIL_FUEL')]]).orders
        def ynab = Mock(YNABService)
        when:
        def ambiguous = SheetzEnrichment.process([tx()], orders, ynab, ['account'] as Set, false, true, false, null, null)
        def preview = SheetzEnrichment.process([tx()], [], ynab, ['account'] as Set, true, true, false, null, null)
        then:
        ambiguous.preview[0].reason == 'ambiguous'
        preview.preview[0].changes.memo.contains('No matching')
        0 * ynab.putTransaction(_, _)
        0 * ynab.getTransaction(_)
    }

    def 'incomplete history suppresses unmatched fallback even in preview'() {
        given: def ynab = Mock(YNABService)
        when: def result = SheetzEnrichment.process([tx()], [], ynab, ['account'] as Set, true, true, false, null, null, false)
        then:
        result.preview == [[transaction_id:'t', reason:'incomplete_history']]
        0 * ynab.putTransaction(_, _)
    }

    def 'configured unmatched category is assigned and invalid category is not written'() {
        given:
        def transaction = tx()
        def current = snapshot(transaction)
        def ynab = Mock(YNABService)
        when:
        def result = SheetzEnrichment.process([transaction], [], ynab, ['account'] as Set, false, false, true, null, selected)
        then:
        1 * ynab.getCategoryCatalog() >> [[id:'other']]
        reads * ynab.getTransaction('t') >>> [current, current + [category_id:'other']]
        writes * ynab.putTransaction('t', [category_id:'other'])
        result.updated == writes
        where:
        selected  | reads | writes
        'other'   | 2     | 1
        'missing' | 0     | 0
    }
}
