package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Unroll

class WalmartEvidenceNormalizer_UT extends Specification {
    Map fixture() { new ObjectMapper().readValue(new File('walmart-helper/testdata/source-evidence.json'),Map) }

    @Unroll
    def 'configured #configured currency fills absent source evidence but rejects #sourceCurrency / #nestedCurrency conflicts'() {
        given:
        def source=fixture()
        source.detail.remove('currencyCode')
        if (sourceCurrency!=null) source.detail.currencyCode=sourceCurrency
        source.detail.data.order.priceDetails.grandTotal.currency=nestedCurrency
        def tx=WalmartFixture.transactions()[0]
        tx.amount=-55000L

        when:
        def order=WalmartEvidenceNormalizer.normalize(source,configured)

        then:
        order.currency==expected
        order.charges*.currency.every { it==expected }
        new TransactionMatcher().findWalmartMatches([tx],[order]).size()==(expected=='USD' ? 1 : 0)

        where:
        sourceCurrency | nestedCurrency | configured | expected
        null           | null           | 'USD'      | 'USD'
        'USD'          | null           | 'USD'      | 'USD'
        'CAD'          | null           | 'USD'      | null
        'USD'          | 'CAD'          | 'USD'      | null
        null           | null           | 'CAD'      | 'CAD'
        'USD'          | null           | 'CAD'      | null
    }

    def 'source repetitions weighted quantities adjustments holds and refunds retain exact meaning in Groovy'() {
        when:
        def order=WalmartEvidenceNormalizer.normalize(fixture())

        then:
        order.totalAmount==110G
        order.items*.grossCents==[8000L,2000L]
        order.items*.quantity==[1.25G,2G]
        order.charges*.cents==[5500L,5500L,-500L]
        order.charges*.key.toSet().size()==3
        order.charges*.date==['2026-11-21','2026-11-21','2026-11-21']
        order.adjustments==[[kind:'tax',cents:500L],[kind:'tip',cents:200L],[kind:'fee',cents:300L]]
        !order.complete
        order.ineligibleReason=='refund_or_unsupported_ledger'
    }

    def 'returned items keep purchases and ledger refunds as separate signed matching events'() {
        given:
        def source=fixture()
        source.detail.data.order.groups_2101[0].items[1].returnId='return-id'
        def lines=source.ledger.data.getOrderLedger.paymentMethodsLedgers[0].transactions[1].transactionLines
        lines[0].rowLines.removeAll { it.lineType=='ORDER_ADJUSTMENT_REFUND' }
        lines << [date:'Nov 23, 2026',rowLines:[[lineType:'ORDER_ADJUSTMENT_REFUND',displayValues:['-$10.56']]]]

        when:
        def order=WalmartEvidenceNormalizer.normalize(source)

        then:
        order.charges*.cents==[5500L,5500L,-1056L]
        order.charges*.date==['2026-11-21','2026-11-21','2026-11-23']
        order.charges*.payment.toSet()==['CREDITCARD:VISA:1234'] as Set
        order.charges*.key.toSet().size()==3
        order.charges.last().description=='Returned items on order: Soap'
        order.items*.grossCents==[8000L,2000L]
        order.totalAmount==110G
        !order.complete
    }

    @Unroll
    def 'refund evidence rejects unsupported signed amounts: #amount'() {
        given:
        def source=fixture()
        source.ledger.data.getOrderLedger.paymentMethodsLedgers[0].transactions[1].transactionLines[0].rowLines.last().displayValues=[amount]

        when:
        WalmartEvidenceNormalizer.normalize(source)

        then:
        thrown(IllegalArgumentException)

        where:
        amount << ['$5.00','-$0.00','-$5.001','-$5','-$1,00.00']
    }

    def 'nested savings never turn reconciled net item costs into a gross exception basis'() {
        given:
        def source=fixture()
        def raw=source.detail.data.order
        raw.priceDetails=[grandTotal:[value:95]]
        raw.groups_2101[0].categories=[]
        raw.groups_2101[0].items[1].priceInfo.linePrice.value=15
        raw.groups_2101[0].priceDetails=[savings:[value:5]]
        def payment=source.ledger.data.getOrderLedger.paymentMethodsLedgers[0]
        payment.transactions=[[chargeType:'FINAL_CHARGES',transactionLines:[[date:'Nov 21, 2026',rowLines:[[lineType:'ORDER_CHARGE',displayValues:['$95.00']]]]]]]

        expect:
        !WalmartEvidenceNormalizer.normalize(source).complete
        WalmartEvidenceNormalizer.normalize(source).ineligibleReason=='discount_basis_unproven'

        when:
        raw.groups_2101[0].priceDetails.savings.value=0

        then:
        WalmartEvidenceNormalizer.normalize(source).complete
    }

    @Unroll
    def 'money rejects unsupported precision and nonfinite values: #value'() {
        when:
        WalmartEvidenceNormalizer.money(value)

        then:
        thrown(Exception)

        where:
        value << [12.345G, Double.NaN, Double.POSITIVE_INFINITY,1000000001G,'12.34',null]
    }

    def 'a net line plus a separately identified discount is not treated as a proven gross basis'() {
        given:
        def source=fixture()
        def raw=source.detail.data.order
        raw.priceDetails=[grandTotal:[value:95], savings:[value:5]]
        raw.groups_2101[0].categories=[]
        raw.groups_2101[0].items[1].priceInfo.linePrice.value=15
        def payment=source.ledger.data.getOrderLedger.paymentMethodsLedgers[0]
        payment.transactions=[[chargeType:'FINAL_CHARGES',transactionLines:[[date:'Nov 21, 2026',rowLines:[[lineType:'ORDER_CHARGE',displayValues:['$95.00']]]]]]]

        when:
        def order=WalmartEvidenceNormalizer.normalize(source)

        then:
        order.items*.grossCents==[8000L,1500L]
        order.totalAmount==95G
        !order.complete
        order.ineligibleReason=='discount_basis_unproven'
    }

    def 'a cancelled group is incomplete rather than a delivered charge basis'() {
        given:
        def source=fixture()
        source.detail.data.order.groups_2101[0].status.statusType='CANCELED'
        def lines=source.ledger.data.getOrderLedger.paymentMethodsLedgers[0].transactions[1].transactionLines[0].rowLines
        lines.removeAll { it.lineType!='ORDER_CHARGE' }

        expect:
        !WalmartEvidenceNormalizer.normalize(source).complete
        WalmartEvidenceNormalizer.normalize(source).ineligibleReason=='unfulfilled_or_unsupported'
    }

    def 'only bounded floating representation noise is tolerated, not fractional cents'() {
        expect:
        WalmartEvidenceNormalizer.money(0.29000000000000004d)==29L
        WalmartEvidenceNormalizer.money(-5G)==-500L
    }

    @Unroll
    def 'invalid final-charge dates cannot become supported charges: #date'() {
        given:
        def source=fixture()
        source.ledger.data.getOrderLedger.paymentMethodsLedgers[0].transactions[1].transactionLines[0].date=date

        when:
        WalmartEvidenceNormalizer.normalize(source)

        then:
        thrown(java.time.format.DateTimeParseException)

        where:
        date << ['Feb 29, 2025','February 29, 2025','Apr 31, 2026','April 31, 2026']
    }

    @Unroll
    def 'valid leap-day final charges retain the exact source date: #date'() {
        given:
        def source=fixture()
        source.ledger.data.getOrderLedger.paymentMethodsLedgers[0].transactions[1].transactionLines[0].date=date

        expect:
        WalmartEvidenceNormalizer.normalize(source).charges*.date==['2024-02-29','2024-02-29','2024-02-29']

        where:
        date << ['Feb 29, 2024','February 29, 2024']
    }
}
