package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Unroll

class WalmartEvidenceNormalizer_UT extends Specification {
    Map fixture() { new ObjectMapper().readValue(new File('walmart-helper/testdata/source-evidence.json'),Map) }

    def 'source repetitions weighted quantities adjustments holds and refunds retain exact meaning in Groovy'() {
        when:
        def order=WalmartEvidenceNormalizer.normalize(fixture())

        then:
        order.totalAmount==110G
        order.items*.grossCents==[8000L,2000L]
        order.items*.quantity==[1.25G,2G]
        order.charges*.cents==[5500L,5500L]
        order.charges*.key.toSet().size()==2
        order.charges*.date==['2026-11-21','2026-11-21']
        order.adjustments==[[kind:'tax',cents:500L],[kind:'tip',cents:200L],[kind:'fee',cents:300L]]
        !order.complete
        order.ineligibleReason=='refund_or_unsupported_ledger'
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

    def 'only bounded floating representation noise is tolerated, not fractional cents'() {
        expect:
        WalmartEvidenceNormalizer.money(0.29000000000000004d)==29L
        WalmartEvidenceNormalizer.money(-5G)==-500L
    }
}
