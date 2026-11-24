package com.ynab.amazon.service

import com.ynab.amazon.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Global candidate components, with identical occurrences treated as a multiset. */
class WalmartLedgerMatcher {
    List<TransactionMatch> match(List<YNABTransaction> transactions, List<WalmartOrder> orders, Map paymentAccounts = [:]) {
        transactions = transactions.findAll { tx ->
            tx.id && !tx.isDeleted() && !tx.isTransfer() && tx.amount != null && tx.amount != 0 && tx.amount % 10 == 0 &&
                (tx.payee_name?.toUpperCase(Locale.ROOT) ==~ /.*(WALMART|WAL-MART).*/) && !tx.payee_name.toUpperCase(Locale.ROOT).contains('TRANSFER')
        }
        def candidates = [:]
        def chargeGroups = [:]
        orders.each { order ->
            settledCharges(order, transactions, paymentAccounts).each { charge ->
                if (charge.currency != 'USD') return
                String group = "${order.orderId}|${charge.payment}|${charge.date}|${charge.cents}|${charge.components ? charge.components*.date.join(',') : ''}"
                if (!chargeGroups[group]) chargeGroups[group] = []
                chargeGroups[group] << [order: order, charge: charge]
            }
        }
        transactions.each { tx ->
            def groups = chargeGroups.findAll { group, entries ->
                compatible(tx, entries[0].charge, paymentAccounts)
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

    private static boolean compatible(YNABTransaction tx, Map charge, Map paymentAccounts) {
        try {
            charge.currency == 'USD' && tx.amount == -((long)charge.cents) * 10L &&
                (charge.components ?: [charge]).every { c ->
                    Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(tx.date), LocalDate.parse(c.date))) <= 14 &&
                        (!paymentAccounts[c.payment] || paymentAccounts[c.payment] == tx.account_id)
                }
        } catch (Exception ignored) { false }
    }

    private static List<Map> settledCharges(WalmartOrder order, List<YNABTransaction> transactions, Map paymentAccounts) {
        List<Map> pairs = []
        order.charges.findAll { it.additionalTip }.each { tip ->
            order.charges.findAll { !it.additionalTip && it.cents > 0 && it.currency == tip.currency && it.payment == tip.payment }.each { base ->
                long days = ChronoUnit.DAYS.between(LocalDate.parse(base.date), LocalDate.parse(tip.date))
                if (days < 0 || days > 14) return
                Map combined = base + [key: base.key + '+' + tip.key, date: tip.date,
                    cents: Math.addExact((long)base.cents, (long)tip.cents), components: [base, tip]]
                // Never replace plausible separately posted charges with an inferred combination.
                if (!transactions.any { compatible(it, base, paymentAccounts) || compatible(it, tip, paymentAccounts) } &&
                    transactions.any { compatible(it, combined, paymentAccounts) }) pairs << combined
            }
        }
        // A ledger occurrence must have exactly one possible partner; the global
        // matcher below still rejects competing orders and bank transactions.
        List<Map> unique = pairs.findAll { pair ->
            pair.components.every { part -> pairs.count { it.components*.key.contains(part.key) } == 1 }
        }
        Set used = unique.collectMany { it.components*.key } as Set
        order.charges.findAll { !used.contains(it.key) } + unique
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
