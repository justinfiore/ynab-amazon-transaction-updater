package com.ynab.amazon.service

import com.ynab.amazon.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Global candidate components, with identical occurrences treated as a multiset. */
class WalmartLedgerMatcher {
    List<TransactionMatch> match(List<YNABTransaction> transactions, List<WalmartOrder> orders, Map paymentAccounts = [:]) {
        def candidates = [:]
        def chargeGroups = [:]
        orders.each { order ->
            order.charges.each { charge ->
                if (charge.currency != 'USD') return
                String group = "${order.orderId}|${charge.payment}|${charge.date}|${charge.cents}"
                if (!chargeGroups[group]) chargeGroups[group] = []
                chargeGroups[group] << [order: order, charge: charge]
            }
        }
        transactions.each { tx ->
            if (!tx.id || tx.isDeleted() || tx.isTransfer() || tx.amount == null || tx.amount == 0 || tx.amount % 10 != 0 ||
                !(tx.payee_name?.toUpperCase(Locale.ROOT) ==~ /.*(WALMART|WAL-MART).*/) || tx.payee_name.toUpperCase(Locale.ROOT).contains('TRANSFER')) return
            def groups = chargeGroups.findAll { group, entries ->
                def c = entries[0].charge
                try {
                    tx.amount == -((long)c.cents) * 10L &&
                        Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(tx.date), LocalDate.parse(c.date))) <= 14 &&
                        (!paymentAccounts[c.payment] || paymentAccounts[c.payment] == tx.account_id)
                } catch (Exception ignored) { false }
            }.keySet() as Set
            if (groups) candidates[tx.id] = [tx: tx, groups: groups]
        }
        List<TransactionMatch> matches = []
        Set<String> visited = [] as Set
        candidates.keySet().sort().each { start ->
            if (visited.contains(start)) return
            Set<String> component = [start] as Set
            Set<String> groups = [] as Set
            boolean expanded = true
            while (expanded) {
                int before = component.size()
                component.each { groups.addAll(candidates[it].groups) }
                candidates.each { id, candidate -> if (candidate.groups.any { groups.contains(it) }) component.add(id) }
                expanded = before != component.size()
            }
            visited.addAll(component)
            def subset = candidates.subMap(component)
            def available = chargeGroups.subMap(groups)
            Map assignment = maximum(subset, available)
            // Removing a transaction->group edge tests for a genuinely different
            // maximum assignment, not an irrelevant permutation of equal charges.
            if (assignment.any { id, entry -> maximum(subset, available, id, entry.group).size() == assignment.size() }) return
            assignment.each { id, entry ->
                String memo = memo(candidates[id].tx, entry.order, entry.charge)
                String reason = entry.charge.cents < 0 ? 'unique_final_refund' : 'unique_final_charge'
                def match = new TransactionMatch(candidates[id].tx, entry.order, memo, 1d, memo == null ? 'memo_overflow_or_invalid_metadata' : reason)
                match.walmartCharge = entry.charge
                matches << match
            }
        }
        matches
    }

    private static Map maximum(Map candidates, Map groups, String excludedId = null, String excludedGroup = null) {
        def slots = groups.collectMany { group, entries -> entries.collect { it + [group: group] } }.sort { it.charge.key }
        Map<Integer, String> occupants = [:]
        Closure<Boolean> augment
        augment = { String id, Set<Integer> seen ->
            for (int i = 0; i < slots.size(); i++) {
                if (!candidates[id].groups.contains(slots[i].group) || (id == excludedId && slots[i].group == excludedGroup) || !seen.add(i)) continue
                if (!occupants.containsKey(i) || augment(occupants[i], seen)) { occupants[i] = id; return true }
            }
            false
        }
        candidates.keySet().sort().each { augment(it, [] as Set) }
        occupants.collectEntries { index, id -> [(id): slots[index]] }
    }

    static String memo(YNABTransaction tx, WalmartOrder order, Map charge = null) {
        if (!(order.orderId ==~ /[A-Za-z0-9_-]{1,100}/) || order.currency != 'USD' || order.totalAmount <= 0 ||
            order.orderUrl != order.orderLink) return null
        try { LocalDate.parse(order.orderDate) } catch (Exception ignored) { return null }
        boolean refund = charge && charge.cents < 0
        String details = refund ? "Walmart refund ${charge.date} | Refund \$${BigDecimal.valueOf(-((long)charge.cents),2)}" :
            "${order.orderDate} | Total \$${order.totalAmount.setScale(2, java.math.RoundingMode.UNNECESSARY)}"
        if (refund && charge.description) details += ' | ' + charge.description.take(200)
        String metadata = "${details} | #${order.orderId} | ${order.orderLink}"
        String current = tx.memo == 'null' ? '' : tx.memo ?: ''
        String result = current == metadata || current.endsWith(' | ' + metadata) ? current : current ? current + ' | ' + metadata : metadata
        result.length() <= 500 ? result : null
    }
}
