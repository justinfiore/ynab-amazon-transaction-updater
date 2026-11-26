package com.ynab.amazon.service

import com.ynab.amazon.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** All financial interpretation of the retrieval-only helper's source evidence. */
class WalmartEvidenceNormalizer {
    static WalmartOrder normalize(Map evidence, String configuredCurrency = 'USD') {
        Map detail = evidence.detail as Map
        Map raw = detail?.data?.order as Map
        Map pricing = raw?.priceDetails as Map
        if (!(raw?.id ==~ /[A-Za-z0-9_-]{1,100}/) || !(raw.orderDate instanceof String) || raw.orderDate.size()<10 || !pricing?.grandTotal) invalid()
        String date = LocalDate.parse(raw.orderDate.substring(0,10)).toString()
        Set currencies = [] as Set
        Closure walk
        walk = { value ->
            if (value instanceof Map) value.each { key,v ->
                if (key in ['currency','currencyCode']) {
                    if (v != null) currencies.add(v)
                } else walk(v)
            }
            else if (value instanceof List) value.each { walk(it) }
        }
        walk(detail)
        String currency = (!currencies || currencies == ([configuredCurrency] as Set)) ? configuredCurrency : null
        long tip = pricing.driverTip ? money(pricing.driverTip.value) : 0L
        long total = pricing.totalWithTip ? money(pricing.totalWithTip.value) : Math.addExact(money(pricing.grandTotal.value),tip)
        if (total<=0 || tip<0) invalid()
        def order = new WalmartOrder(orderId:raw.id,orderDate:date,currency:currency,totalAmount:BigDecimal.valueOf(total,2),
            complete:true,items:[],charges:[],adjustments:[])
        order.orderUrl = order.orderLink
        def incomplete = { String why -> order.complete=false; order.ineligibleReason=why }
        if (currency != 'USD') incomplete(currency ? 'unsupported_currency' : 'currency_unproven')
        def adjustment = { String kind, Object value ->
            long cents=money(value)
            if (cents<0) invalid()
            if (cents) order.adjustments << [kind:kind,cents:cents]
        }
        if (pricing.taxTotal) adjustment('tax',pricing.taxTotal.value)
        if (pricing.driverTip) adjustment('tip',pricing.driverTip.value)
        (pricing.fees ?: []).each { adjustment('fee',it.value) }
        boolean hasSavings = pricing.savings && money(pricing.savings.value)!=0
        Map items=[:]
        Set<String> returnedDescriptions=[] as Set
        if (!(raw.groups_2101 instanceof List)) invalid()
        raw.groups_2101.each { group ->
            if (!(group.status?.statusType in ['DELIVERED','PICKED_UP'])) incomplete('unfulfilled_or_unsupported')
            if (group.priceDetails?.savings && money(group.priceDetails.savings.value)!=0) hasSavings=true
            def displayed=(group.items ?: []) + (group.categories ?: []).collectMany { it.items ?: [] } +
                (group.subGroups ?: []).collectMany { sub -> (sub.categories ?: []).collectMany { it.items ?: [] } }
            displayed.each { item ->
                if (!item.id || !item.productInfo?.name || item.productInfo.name.size()>1000 || !item.priceInfo?.linePrice || !(item.quantity instanceof Number)) invalid()
                if (item.returnId) {
                    incomplete('returned_items')
                    returnedDescriptions.add(item.productInfo.name)
                }
                BigDecimal quantity=new BigDecimal(item.quantity.toString())
                if (quantity<=0) invalid()
                long gross=money(item.priceInfo.linePrice.value)
                if (gross<0) invalid()
                Map next=[itemId:item.id,productId:item.productInfo.usItemId,title:item.productInfo.name,quantity:quantity,grossCents:gross]
                if (items[item.id] && items[item.id]!=next) invalid()
                items[item.id]=next
            }
        }
        order.items=items.keySet().sort().collect { new WalmartOrderItem(items[it]) }
        if (hasSavings) {
            // Savings may already be included in line prices or repeated at group/order level.
            // Only the remaining reduction belongs to the default category, not exception items.
            long itemTotal=(order.items.sum { it.grossCents } ?: 0) as long
            long costs=(order.adjustments.sum { it.cents } ?: 0) as long
            long discount=itemTotal+costs-total
            if (discount>0) order.adjustments << [kind:'discount',cents:discount]
        }
        def payments=evidence.ledger?.data?.getOrderLedger?.paymentMethodsLedgers
        if (!(payments instanceof List)) invalid()
        Map ordinals=[:].withDefault { 0 }
        payments.each { payment ->
            if (!(payment.paymentType instanceof String) || !(payment.transactions instanceof List)) invalid()
            def suffix=(payment.description ?: '') =~ /(?:^|\D)(\d{4})$/
            String identity=payment.paymentType+':'+(payment.cardType ?: '')+':'+(suffix.find() ? suffix.group(1) : '')
            payment.transactions.each { transaction ->
                if (transaction.chargeType!='FINAL_CHARGES') return
                if (!(transaction.transactionLines instanceof List)) invalid()
                transaction.transactionLines.each { line ->
                    LocalDate charged
                    try { charged=LocalDate.parse(line.date,DateTimeFormatter.ofPattern('MMM d, uuuu',Locale.US).withResolverStyle(ResolverStyle.STRICT)) }
                    catch (Exception ignored) { charged=LocalDate.parse(line.date,DateTimeFormatter.ofPattern('MMMM d, uuuu',Locale.US).withResolverStyle(ResolverStyle.STRICT)) }
                    if (!(line.rowLines instanceof List)) invalid()
                    line.rowLines.each { row ->
                        boolean refund=row.lineType=='ORDER_ADJUSTMENT_REFUND'
                        if (refund) incomplete('refund_or_unsupported_ledger')
                        else if (!(row.lineType in ['ORDER_CHARGE','ADDITIONAL_DRIVER_TIP'])) { incomplete('refund_or_unsupported_ledger'); return }
                        if (!(row.displayValues instanceof List) || row.displayValues.size()!=1 ||
                            !(row.displayValues[0] ==~ /-?\$(?:0|[1-9]\d*|[1-9]\d{0,2}(?:,\d{3})+)\.\d{2}/)) invalid()
                        long cents=money(new BigDecimal(row.displayValues[0].replace('$','').replace(',','')))
                        if (refund ? cents>=0 : cents<=0) invalid()
                        String base="${raw.id}|${identity}|${charged}|${cents}"
                        ordinals[base]++
                        order.charges << [key:"${base}|${ordinals[base]}".toString(),date:charged.toString(),currency:currency,payment:identity,cents:cents]
                        if (row.lineType=='ADDITIONAL_DRIVER_TIP') order.charges.last().additionalTip=true
                    }
                }
            }
        }
        // The ledger does not identify which return an adjustment belongs to.
        // Preserve the order's returned-product descriptions as context, not an item-level allocation.
        order.charges.findAll { it.cents<0 }.each {
            it.description=returnedDescriptions ? 'Returned items on order: '+returnedDescriptions.sort().join('; ') : 'Order adjustment refund'
        }
        if (!order.items || !order.charges) incomplete('missing_items_or_charges')
        order
    }

    static long money(Object value) {
        if (!(value instanceof Number)) invalid()
        BigDecimal amount=new BigDecimal(value.toString())
        if (amount.abs()>1000000000G) invalid()
        BigDecimal scaled=amount*100G
        BigDecimal rounded=scaled.setScale(0,java.math.RoundingMode.HALF_UP)
        if ((scaled-rounded).abs()>0.0000001G) invalid()
        rounded.longValueExact()
    }
    private static void invalid() { throw new IllegalArgumentException('Unsupported Walmart source evidence') }
}
