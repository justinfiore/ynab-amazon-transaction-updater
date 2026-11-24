package com.ynab.amazon.service

import com.ynab.amazon.model.*

/** Exact bookkeeping allocation, independent of model inference and HTTP. */
class WalmartAllocation {
    static Map<String, Map> categories(List<Map> catalog, List<Map> selected, String defaultId) {
        if (!selected || selected.any { !it.id } || selected*.id.toSet().size() != selected.size() || !selected*.id.contains(defaultId)) {
            throw new IllegalStateException('Invalid Walmart category shortlist/default')
        }
        def eligible = catalog.findAll { !it.deleted && !it.hidden && !it.internal && !it.group_internal && !it.group_hidden && !it.group_deleted }.collectEntries { [(it.id): it] }
        if (selected.any { !eligible[it.id] }) throw new IllegalStateException('Walmart category is absent, hidden, deleted or special')
        selected.collectEntries { [(it.id): [id: it.id, name: eligible[it.id].name, group_name: eligible[it.id].group_name, description: it.description ?: '']] }
    }

    static Map plan(WalmartOrder order, List<TransactionMatch> matches, String defaultId, Map decisions, Set eligibleAccounts, boolean allowApprovedUncategorized = false) {
        def skip = { String why -> [eligible: false, reason: why, operations: []] }
        if (!order.complete || order.currency != 'USD' || order.totalAmount <= 0) return skip(order.ineligibleReason ?: 'incomplete_order')
        if (order.charges.any { it.cents<=0 }) return skip('refund_or_unsupported_ledger')
        if (!order.charges || matches.size() != order.charges.size() || matches*.walmartCharge*.key.toSet() != order.charges*.key.toSet() ||
            matches*.ynabTransaction*.id.toSet().size() != matches.size()) return skip('missing_or_ambiguous_payment')
        if (matches.any { !it.proposedMemo || !eligible(it.ynabTransaction, eligibleAccounts, false, allowApprovedUncategorized) }) return skip('ineligible_parent')
        long total = order.totalAmount.movePointRight(2).longValueExact()
        long chargeTotal = order.charges.sum { (long) it.cents } as long
        if (order.items.any { it.grossCents == null || it.grossCents < 0 }) return skip('unknown_item_basis')
        long itemTotal = (order.items.sum { it.grossCents } ?: 0) as long
        long adjustments = (order.adjustments.sum { it.kind == 'discount' ? -(long)it.cents : (long)it.cents } ?: 0) as long
        if (chargeTotal != total || itemTotal + adjustments != total) return skip('unreconciled_costs')
        def exceptions = order.items.findAll { decisions[it.itemId]?.category_id != defaultId && it.grossCents > 0 }.sort { it.itemId }
        long exceptionTotal = (exceptions.sum { it.grossCents } ?: 0) as long
        if (exceptionTotal > total) return skip('negative_default')
        def remaining = exceptions.collect { item -> [item: item, left: item.grossCents] }
        def operations = []
        matches.sort(false) { a, b ->
            (b.walmartCharge.cents <=> a.walmartCharge.cents) ?: (a.walmartCharge.date <=> b.walmartCharge.date) ?:
                (a.walmartCharge.key <=> b.walmartCharge.key) ?: (a.ynabTransaction.id <=> b.ynabTransaction.id)
        }.each { match ->
            long capacity = match.walmartCharge.cents as long
            List<Map> lines = []
            remaining.each { pending ->
                long amount = Math.min(capacity, pending.left as long)
                if (!amount) return
                def item = pending.item
                lines << [item_id: item.itemId, product_id: item.productId, cents: amount,
                          category_id: decisions[item.itemId]?.category_id, memo: item.title.take(500)]
                capacity -= amount
                pending.left -= amount
            }
            if (capacity > 0) lines << [cents: capacity, category_id: defaultId, memo: 'Default Walmart Category']
            operations << [transaction_id: match.ynabTransaction.id, charge_key: match.walmartCharge.key,
                           memo: match.proposedMemo, lines: lines]
        }
        [eligible: true, operations: operations, decisions: decisions]
    }

    static Map refundPlan(TransactionMatch match, String defaultId, Map decisions, Set accounts, boolean allowApprovedUncategorized = false) {
        if (!match.proposedMemo || !eligible(match.ynabTransaction, accounts, true, allowApprovedUncategorized)) return [eligible:false, reason:'refund_metadata_only']
        Map refund=match.walmartCharge
        [eligible:true, reason:'refund_category', operations:[[transaction_id:match.ynabTransaction.id, charge_key:refund.key,
            memo:match.proposedMemo, lines:[[cents:-((long)refund.cents), category_id:decisions[refund.key]?.category_id ?: defaultId]]]]]
    }

    static boolean eligible(YNABTransaction tx, Set accounts, boolean refund = false, boolean allowApprovedUncategorized = false) {
        (tx.getApproved() == 'false' || (allowApprovedUncategorized && tx.getApproved() == 'true' && tx.category_id == null)) &&
            tx.getDeleted() == 'false' && tx.amount != null && (refund ? tx.amount > 0 : tx.amount < 0) && tx.amount % 10 == 0 &&
            !tx.isTransfer() && tx.getCleared() in ['cleared', 'uncleared'] && !tx.subtransactions && accounts.contains(tx.account_id)
    }

    static Map payload(Map baseline, Map operation) {
        Map result = [memo: operation.memo, approved: false]
        if (operation.lines.size() == 1) result.category_id = operation.lines[0].category_id
        else {
            result.category_id = null
            result.subtransactions = operation.lines.collect { [amount: -((long)it.cents) * 10L, category_id: it.category_id, memo: it.memo] }
        }
        result
    }
}
