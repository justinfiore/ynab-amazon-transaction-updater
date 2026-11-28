package com.ynab.budgetassistant.service

import com.ynab.budgetassistant.model.*
import spock.lang.Specification
import spock.lang.Unroll

class WalmartContracts_UT extends Specification {
    def matcher = new AmazonTransactionMatcher()

    @Unroll
    def 'driver-tip combinations preserve ambiguity and source boundaries: #variant'() {
        given:
        def order=WalmartFixture.order()
        def base=order.charges[0]+[key:'base',cents:4464L,date:'2026-11-15']
        def tip=base+[key:'tip',cents:218L,date:'2026-11-16',additionalTip:true]
        order.charges=[base,tip]
        def tx=WalmartFixture.transactions()[0]
        tx.amount=-46820L
        tx.date='2026-11-17'
        def txs=[tx]
        def orders=[order]
        Map accounts=[:]
        if (variant=='two bases') order.charges << base+[key:'base2']
        if (variant=='two tips') order.charges << tip+[key:'tip2']
        if (variant=='different payment') tip.payment='other'
        if (variant=='different currency') tip.currency='CAD'
        if (variant=='earlier tip') tip.date='2026-11-14'
        if (variant=='distant tip') tip.date='2026-11-30'
        if (variant=='outside base date') tx.date='2026-11-30'
        if (variant=='date boundary') tx.date='2026-11-29'
        if (variant=='mapped elsewhere') accounts[base.payment]='elsewhere'
        if (variant=='fractional cent') tx.amount--
        if (variant=='separately posted base' || variant=='separately posted tip') {
            txs << new YNABTransaction(id:'separate',date:tx.date,payee_name:'WALMART',account_id:tx.account_id,
                amount:variant=='separately posted base' ? -44640L : -2180L)
        }
        if (variant=='two bank candidates') txs << new YNABTransaction(id:'duplicate',date:tx.date,amount:tx.amount,payee_name:'WALMART',account_id:tx.account_id)
        if (variant=='competing order') {
            def other=WalmartFixture.order(); other.orderId='other'
            other.charges=[base+[key:'other',cents:4682L]]
            orders << other
        }

        expect:
        matcher.findWalmartMatches(txs,orders,accounts).findAll { it.ynabTransaction.id==tx.id }.size()==expected

        where:
        variant                  | expected
        'unique'                 | 1
        'date boundary'          | 1
        'two bases'              | 0
        'two tips'               | 0
        'different payment'      | 0
        'different currency'     | 0
        'earlier tip'            | 0
        'distant tip'            | 0
        'outside base date'      | 0
        'mapped elsewhere'       | 0
        'fractional cent'        | 0
        'separately posted base' | 0
        'separately posted tip'  | 0
        'two bank candidates'    | 0
        'competing order'        | 0
    }

    def 'refunds match inflows independently of purchases with exact amounts dates accounts and ambiguity guards'() {
        given:
        def order=WalmartFixture.order()
        order.charges=[order.charges[0],order.charges[0]+[key:'refund',date:'2026-11-09',cents:-1056L]]
        def purchase=WalmartFixture.transactions()[0]
        def refund=new YNABTransaction(id:'refund',date:'2026-11-11',amount:10560L,payee_name:'WALMART',account_id:'account',memo:'User refund note')

        when:
        def matches=matcher.findWalmartMatches([purchase,refund],[order])
        def credit=matches.find { it.ynabTransaction.id=='refund' }

        then:
        matches.size()==2
        credit.walmartCharge.cents==-1056L
        credit.matchReason=='unique_final_refund'
        credit.proposedMemo=='User refund note | Walmart refund 2026-11-09 | Refund $10.56 | #123 | https://www.walmart.com/orders/123'
        !WalmartAllocation.plan(order,matches,'g',[:],['account'] as Set).eligible
        matcher.findWalmartMatches([refund],[order],[(order.charges[0].payment):'elsewhere']).empty

        when: 'a competing order with the same refund is ambiguous, while the unrelated purchase still matches'
        def other=WalmartFixture.order(); other.orderId='other'
        other.charges=[order.charges[1]+[key:'other-refund']]
        def ambiguous=matcher.findWalmartMatches([purchase,refund],[order,other])

        then:
        ambiguous*.ynabTransaction*.id==['large']

        when:
        refund.amount=10561L

        then:
        matcher.findWalmartMatches([refund],[order]).empty

        when:
        refund.amount=-10560L

        then:
        matcher.findWalmartMatches([refund],[order]).empty

        when:
        refund.amount=10560L
        refund.date='2026-11-24'

        then:
        matcher.findWalmartMatches([refund],[order]).empty

        when:
        refund.date='2026-11-23'
        refund.memo=credit.proposedMemo

        then:
        matcher.findWalmartMatches([refund],[order])[0].proposedMemo==credit.proposedMemo

        when:
        refund.memo='x'*450

        then:
        matcher.findWalmartMatches([refund],[order])[0].proposedMemo==null
    }

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

        when:
        order.charges=[
            [key:'late',date:'2026-11-22',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:5000L],
            [key:'same-b',date:'2026-11-21',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:2500L],
            [key:'same-a',date:'2026-11-21',payment:'CREDITCARD:VISA:1234',currency:'USD',cents:2500L]
        ]
        txs=order.charges.collect { charge ->
            new YNABTransaction(id:'tx-'+charge.key,date:charge.date,amount:-((long)charge.cents)*10L,payee_name:'WALMART',account_id:'account',cleared:'cleared',approved:'false',deleted:'false')
        }
        def tied=WalmartAllocation.plan(order,matcher.findWalmartMatches(txs.reverse(),[order]),'g',[a:[category_id:'g'],b:[category_id:'h']],['account'] as Set)

        then:
        tied.operations*.transaction_id==['tx-late','tx-same-b','tx-same-a']
    }

}
