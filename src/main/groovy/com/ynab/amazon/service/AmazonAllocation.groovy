package com.ynab.amazon.service

import com.ynab.amazon.model.AmazonOrder

/** Amazon accounting: known cents only. Category scores never establish financial evidence. */
class AmazonAllocation {
    static boolean integer(Object n) { n instanceof Number && new BigDecimal(n.toString()).scale() <= 0 && n >= 0 && n <= 1000000000000L }

    static Map<String,Long> distribute(long cents, Map<String,Long> weights) {
        if (cents < 0 || weights.values().any { it < 0 }) throw new IllegalArgumentException('negative_allocation')
        BigInteger total = weights.values().inject(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) }
        if (total == 0 && cents != 0) throw new IllegalArgumentException('zero_weights')
        Map result = [:]
        Map remainder = [:]
        weights.each { id,n ->
            BigInteger[] qr = total == 0 ? [BigInteger.ZERO,BigInteger.ZERO] as BigInteger[] : (BigInteger.valueOf(cents) * BigInteger.valueOf(n)).divideAndRemainder(total)
            result[id] = qr[0].longValueExact(); remainder[id] = qr[1]
        }
        long left = cents - ((result.values().sum() ?: 0) as long)
        remainder.keySet().sort { a,b -> (remainder[b] <=> remainder[a]) ?: (a <=> b) }.take((int)left).each { result[it]++ }
        result
    }

    static Map costs(Map evidence) {
        def skip = { String reason -> [eligible:false,reason:reason] }
        if (evidence.version != 1 || evidence.complete != true) return skip(evidence.reason ?: 'incomplete_source')
        if (evidence.currency != 'USD') return skip('unsupported_currency')
        List items = evidence.items ?: []
        if (!items || items.any { !it.itemId || !it.title || !integer(it.cents) || (!evidence.final_net && (!integer(it.quantity) || it.quantity == 0)) } ||
            items*.itemId.toSet().size() != items.size()) return skip('unknown_item_basis')
        List adjustments = evidence.adjustments ?: []
        if (evidence.adjustments_distinct != true || adjustments.any { !it.id || !(it.kind in ['discount','tax','shipping','fee']) ||
            !(it.cents instanceof Number) || !integer(Math.abs(it.cents as long)) || it.cents != (it.cents as long) ||
            (it.kind == 'discount' ? it.cents > 0 : it.cents < 0) } || adjustments*.id.toSet().size() != adjustments.size()) return skip('overlapping_or_unknown_adjustments')
        if (evidence.final_net && adjustments) return skip('overlapping_or_unknown_adjustments')
        Map<String,Long> net = items.collectEntries { [(it.itemId):(it.cents as long)] }
        try {
            List discounts = adjustments.findAll { it.kind == 'discount' }
            (discounts.findAll { it.item_id } + discounts.findAll { !it.item_id }).each { adjustment ->
                Set ids = adjustment.item_id ? [adjustment.item_id] as Set : (adjustment.item_ids ?: net.keySet()) as Set
                if (!ids || !net.keySet().containsAll(ids)) throw new IllegalArgumentException('unknown_discount_item')
                Map parts = distribute(-(adjustment.cents as long), net.findAll { id,n -> ids.contains(id) })
                parts.each { id,n -> net[id] -= n; if (net[id] < 0) throw new IllegalArgumentException('negative_item_cost') }
            }
            Map result = new LinkedHashMap(net)
            adjustments.findAll { it.kind != 'discount' }.each { adjustment ->
                distribute(adjustment.cents as long,net).each { id,n -> result[id] += n }
            }
            if (!integer(evidence.full_total_cents) || (result.values().sum() ?: 0) != evidence.full_total_cents) return skip('unreconciled_costs')
            [eligible:true,costs:result,items:items]
        } catch (IllegalArgumentException e) { skip(e.message) }
    }

    static Map plan(AmazonOrder order, Map event, List<Map> events, Map decisions) {
        boolean refund = event.signed_cents > 0
        Map evidence = refund ? event.refund_evidence ?: [:] : order.financialEvidence
        if (refund && !evidence.items) return [eligible:false,reason:'refund_items_unknown']
        Map calculated = costs(evidence)
        if (!calculated.eligible) return refund ? calculated + [reason:calculated.reason == 'unreconciled_costs' ? 'unreconciled_refund' : calculated.reason] : calculated
        long bank = Math.abs(event.signed_cents as long)
        if (refund) {
            if (evidence.mixed_tender || evidence.full_total_cents != bank) return [eligible:false,reason:'unreconciled_refund']
        } else {
            List charges = events.findAll { it.order_id == order.orderId && it.signed_cents < 0 }
            boolean orderBacked = evidence.source == 'python' && evidence.purchase_eligible == true &&
                evidence.payment_identity && evidence.payment_identity == event.payment_identity && event.currency == 'USD'
            if (!integer(evidence.gift_cents) || evidence.reward_cents != 0 || bank + evidence.gift_cents != evidence.full_total_cents ||
                (orderBacked ? charges.size() > 1 || charges.any { charge ->
                    charge.competition_only == true || !charge.key ||
                        ['signed_cents','payment_identity','currency'].any { charge[it] != event[it] }
                } : event.source == 'order' || evidence.payments_complete != true || charges.size() != 1 || charges[0].key != event.key))
                return [eligible:false,reason:'unsupported_payment_coverage']
        }
        Map amounts = refund ? calculated.costs : distribute(bank,calculated.costs)
        List lines = calculated.items.findAll { amounts[it.itemId] > 0 }.collect { item ->
            [item_id:item.itemId,product_id:item.productId,cents:amounts[item.itemId],category_id:decisions[item.itemId]?.category_id,
                memo:AmazonMemos.item(item,refund)]
        }
        if (!lines || lines.sum { it.cents } != bank) return [eligible:false,reason:'allocation_mismatch']
        [eligible:true,lines:lines,items:calculated.items,decisions:decisions]
    }

    static Map payload(Map event, String memo, Map plan) {
        Map changes = [memo:memo,approved:false,category_id:null]
        if (!plan.eligible) return changes
        if (plan.lines.size() == 1) changes.category_id = plan.lines[0].category_id
        else changes.subtransactions = plan.lines.collect { line ->
            [amount:line.cents * (event.signed_cents > 0 ? 10L : -10L),category_id:line.category_id,memo:line.memo]
        }
        changes
    }
}
