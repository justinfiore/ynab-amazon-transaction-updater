package com.ynab.amazon.service

import com.ynab.amazon.model.*
import spock.lang.Specification
import spock.lang.Unroll

class WalmartContracts_UT extends Specification {
    def matcher = new TransactionMatcher()

    def 'global matching refuses cross-order and oversubscribed ambiguity regardless of input order'() {
        given:
        def a = WalmartFixture.order()
        def b = WalmartFixture.order(); b.orderId='other'
        b.charges=b.charges.collect { it + [key:'other-'+it.key] }
        def txs = WalmartFixture.transactions()

        expect:
        matcher.findWalmartMatches(txs,[a,b]).empty
        matcher.findWalmartMatches(txs.reverse(),[b,a]).empty

        when:
        a.charges=[a.charges[0],a.charges[0]+[key:'equal-second']]
        def equal = txs[0]
        def second = new YNABTransaction(id:'second',date:equal.date,amount:equal.amount,payee_name:'WALMART')
        def third = new YNABTransaction(id:'third',date:equal.date,amount:equal.amount,payee_name:'WALMART')

        then:
        matcher.findWalmartMatches([equal,second],[a])*.walmartCharge*.key.toSet().size()==2
        matcher.findWalmartMatches([equal,second,third],[a]).empty
    }

    def 'account evidence disambiguates payment methods and a one-milliunit difference is never rounded'() {
        given:
        def order=WalmartFixture.order()
        order.charges=[order.charges[0],order.charges[0]+[key:'second',payment:'other']]
        def tx=WalmartFixture.transactions()[0]

        expect:
        matcher.findWalmartMatches([tx],[order]).empty
        matcher.findWalmartMatches([tx],[order],[(order.charges[0].payment):'account',other:'elsewhere']).size()==1

        when:
        tx.amount--

        then:
        matcher.findWalmartMatches([tx],[order]).empty
    }

    @Unroll
    def 'metadata preserves user text and rejects unsafe or oversized links: #variant'() {
        given:
        def order=WalmartFixture.order()
        def tx=WalmartFixture.transactions()[0]
        if (variant=='unsafe') order.orderUrl='https://evil.test/order'
        if (variant=='overflow') tx.memo='x'*450
        if (variant=='deleted') tx.deleted='true'
        def matches=matcher.findWalmartMatches([tx],[order])

        expect:
        variant=='deleted' ? matches.empty : matches[0].proposedMemo==null

        where:
        variant << ['unsafe','overflow','deleted']
    }

    @Unroll
    def 'exception placement reconciles independently expected cents: #household / #discount'() {
        given:
        def order=WalmartFixture.order()
        order.items[0].grossCents=food
        order.items[1].grossCents=household
        order.adjustments=discount ? [[kind:'discount',cents:discount]] : []
        order.totalAmount=BigDecimal.valueOf(food+household-discount,2)
        order.charges[0].cents=first
        order.charges[1].cents=second
        def txs=WalmartFixture.transactions()
        txs[0].amount=-first*10L; txs[1].amount=-second*10L
        def matches=matcher.findWalmartMatches(txs,[order])
        Map plan=WalmartAllocation.plan(order,matches,'g',[a:[category_id:'g'],b:[category_id:category]],['account'] as Set)

        expect:
        plan.eligible==eligible
        if (eligible) {
            assert plan.operations*.lines.collect { lines -> lines.collect { [it.cents,it.category_id] } }==expected
            assert plan.operations.collect { WalmartAllocation.payload([:],it) }.every { !it.approved }
            assert WalmartAllocation.plan(order,matches.reverse(),'g',[a:[category_id:'g'],b:[category_id:category]],['account'] as Set)==plan
        }

        where:
        food | household | discount | first | second | category | eligible | expected
        8000 | 2000      | 0        | 7000  | 3000   | 'h'      | true     | [[[2000,'h'],[5000,'g']],[[3000,'g']]]
        8000 | 2000      | 500      | 6500  | 3000   | 'h'      | true     | [[[2000,'h'],[4500,'g']],[[3000,'g']]]
        2000 | 8000      | 0        | 7000  | 3000   | 'h'      | true     | [[[7000,'h']],[[1000,'h'],[2000,'g']]]
        0    | 10000     | 0        | 7000  | 3000   | null     | true     | [[[7000,null]],[[3000,null]]]
        8000 | 2000      | 0        | 7000  | 3000   | 'g'      | true     | [[[7000,'g']],[[3000,'g']]]
        1000 | 5000      | 2000     | 3000  | 1000   | 'h'      | false    | []
    }

    @Unroll
    def 'whole-order eligibility prohibits partial allocation for #reason'() {
        given:
        def order=WalmartFixture.order()
        def txs=WalmartFixture.transactions()
        switch(reason) {
            case 'approved': txs[0].approved='true'; break
            case 'split': txs[0].subtransactions=[[id:'human',amount:-70000]]; break
            case 'reconciled': txs[0].cleared='reconciled'; break
            case 'tracking': txs[0].account_id='tracking'; break
            case 'missing payment': txs.removeLast(); break
            case 'unexplained': order.items[0].grossCents=7999; break
            case 'refund': order.complete=false; break
        }

        expect:
        !WalmartAllocation.plan(order,matcher.findWalmartMatches(txs,[order]),'g',[:],['account'] as Set).eligible

        where:
        reason << ['approved','split','reconciled','tracking','missing payment','unexplained','refund']
    }

    def 'category validation distinguishes stable IDs from names and fails closed on unavailable default'() {
        given:
        def catalog=[[id:'g',name:'New name'],[id:'h',name:'New name']]

        expect:
        WalmartAllocation.categories(catalog,[[id:'g'],[id:'h',description:'not food']],'g').h.description=='not food'

        when:
        WalmartAllocation.categories(catalog,[[id:'g'],[id:'g']],'g')

        then:
        thrown(IllegalStateException)

        when:
        WalmartAllocation.categories([[id:'g',hidden:true]],[[id:'g']],'g')

        then:
        thrown(IllegalStateException)

        when:
        WalmartAllocation.categories(catalog,[[id:'g']],'missing')

        then:
        thrown(IllegalStateException)

        when:
        WalmartAllocation.categories(catalog,[], 'g')

        then:
        thrown(IllegalStateException)

        when:
        def renamed=WalmartAllocation.categories([[id:'g',name:'Renamed',group_name:'Food']],[[id:'g']],'g')

        then:
        renamed.g.id=='g'
        renamed.g.name=='Renamed'
    }

    def 'three charges fill exceptions in descending capacity and equal capacities tie by date then key'() {
        given:
        def order=WalmartFixture.order()
        order.items[0].grossCents=4000L
        order.items[1].grossCents=6000L
        order.totalAmount=100.00
        order.charges=[[key:'late',date:'2026-11-22',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:2500L],
            [key:'early-b',date:'2026-11-21',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:3500L],
            [key:'early-a',date:'2026-11-21',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:4000L]]
        def txs=order.charges.collect { charge ->
            new YNABTransaction(id:'tx-'+charge.key,date:charge.date,amount:-((long)charge.cents)*10L,payee_name:'WALMART',account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
        }
        def matches=matcher.findWalmartMatches(txs.reverse(),[order])

        when:
        def plan=WalmartAllocation.plan(order,matches,'g',[a:[category_id:'g'],b:[category_id:'h']],['account'] as Set)

        then:
        plan.operations*.transaction_id==['tx-early-a','tx-early-b','tx-late']
        plan.operations*.lines.collect { lines -> lines.collect { [it.cents,it.category_id] } }==[[[4000L,'h']],[[2000L,'h'],[1500L,'g']],[[2500L,'g']]]
        plan.operations.sum { it.lines.sum { it.cents } }==10000L
    }

}
